package de.hglabor.snorlaxboss.entity

import de.hglabor.snorlaxboss.entity.player.ModifiedPlayer
import de.hglabor.snorlaxboss.extension.*
import de.hglabor.snorlaxboss.network.NetworkManager
import de.hglabor.snorlaxboss.network.NetworkManager.BOOM_SHAKE_PACKET
import de.hglabor.snorlaxboss.particle.Attacks
import de.hglabor.snorlaxboss.particle.ParticleManager
import de.hglabor.snorlaxboss.render.camera.CameraShaker
import de.hglabor.snorlaxboss.sound.SoundManager
import de.hglabor.snorlaxboss.utils.CustomHitBox
import de.hglabor.snorlaxboss.utils.UUIDWrapper
import de.hglabor.snorlaxboss.utils.weightedCollection
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import net.minecraft.block.BlockState
import net.minecraft.command.argument.EntityAnchorArgumentType
import net.minecraft.entity.*
import net.minecraft.entity.ai.pathing.EntityNavigation
import net.minecraft.entity.attribute.DefaultAttributeContainer
import net.minecraft.entity.attribute.EntityAttribute
import net.minecraft.entity.attribute.EntityAttributeInstance
import net.minecraft.entity.attribute.EntityAttributes
import net.minecraft.entity.boss.BossBar
import net.minecraft.entity.boss.ServerBossBar
import net.minecraft.entity.damage.DamageSource
import net.minecraft.entity.data.DataTracker
import net.minecraft.entity.data.TrackedData
import net.minecraft.entity.data.TrackedDataHandlerRegistry
import net.minecraft.entity.mob.MobEntity
import net.minecraft.entity.mob.PathAwareEntity
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.nbt.NbtCompound
import net.minecraft.particle.ParticleTypes
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvent
import net.minecraft.sound.SoundEvents
import net.minecraft.text.Text
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Box
import net.minecraft.world.World
import net.silkmc.silk.core.entity.directionVector
import net.silkmc.silk.core.entity.modifyVelocity
import net.silkmc.silk.core.kotlin.ticks
import net.silkmc.silk.core.task.infiniteMcCoroutineTask
import net.silkmc.silk.core.task.mcCoroutineTask
import software.bernie.geckolib.animatable.GeoEntity
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache
import software.bernie.geckolib.core.animation.AnimatableManager
import software.bernie.geckolib.core.animation.Animation
import software.bernie.geckolib.core.animation.AnimationController
import software.bernie.geckolib.core.animation.RawAnimation
import software.bernie.geckolib.core.`object`.PlayState
import software.bernie.geckolib.util.GeckoLibUtil
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class Snorlax(entityType: EntityType<out PathAwareEntity>, world: World) : PathAwareEntity(entityType, world),
    GeoEntity {
    private val factory = GeckoLibUtil.createInstanceCache(this)
    private val bossBar =
        ServerBossBar(Text.translatable("entity.snorlaxboss.snorlax"), BossBar.Color.BLUE, BossBar.Style.PROGRESS)
    private val HIT_DISTANCE = 6.0f
    private val RUN_DISTANCE = 3.5f

    var forceAnimationReset = false

    private var isDebug: Boolean
        get() = this.dataTracker.get(IS_DEBUG)
        set(value) = this.dataTracker.set(IS_DEBUG, value)

    private var task: Task? = null
    var customHitBox: EntityDimensions? = null
    var attack: Attack
        get() = this.dataTracker.get(ATTACK)
        set(value) {
            this.dataTracker.set(ATTACK, value)
            if (!this.world.isClient()) {
                task?.onDisable()
                task = value.supplier.invoke(this)
                task?.onEnable()
                /*world.server?.broadcastText {
                    text(task?.name!!) {
                        color = 0x5eff00
                    }
                }*/
            }
        }

    init {
        attack = attack
    }

    enum class Attack(
        val animation: RawAnimation, val supplier: (Snorlax) -> Task
    ) {
        SHAKING("shaking".play(), Snorlax::ShakingTask),
        IDLE("idle".play(), Snorlax::IdleTargetTask),
        BEAM("beam".play(), Snorlax::BeamTask),
        CHECK_TARGET("check-target".loop(), Snorlax::CheckTargetTask),
        PUNCH("punch".hold(), Snorlax::PunchTask),
        MULTIPLE_PUNCH("punch".hold(), Snorlax::MultiplePunchTask),
        RUN("run".loop(), Snorlax::RunTask),
        BELLY_FLOP("belly-flop".hold(), Snorlax::BellyFlopTask),
        SLEEP(
            RawAnimation.begin()
                .then("animation.hglabor.sleep", Animation.LoopType.PLAY_ONCE)
                .thenLoop("animation.hglabor.sleep-idle"), Snorlax::SleepTask
        ),
        JUMP("jump".hold(), Snorlax::JumpTask),
        INHALE("inhale".play(), Snorlax::InhaleTask);

    }

    companion object {
        fun createAttributes(): DefaultAttributeContainer.Builder {
            return MobEntity.createLivingAttributes()
                .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 35.0)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.23000000417232513)
                .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 23.0) //Warden macht 30
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 500.0) //Wie Warden
                .add(EntityAttributes.GENERIC_ARMOR, 4.0)
                .add(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE, KNOCKBACK_RESISTANCE_BASE)
                .add(EntityAttributes.GENERIC_ATTACK_KNOCKBACK, 1.5)
                .add(EntityAttributes.GENERIC_ARMOR, 8.0) //Doppel wither
        }

        const val KNOCKBACK_RESISTANCE_BASE = 0.6000000238418579

        val STANDING_DIMENSIONS: EntityDimensions = EntityDimensions.changing(3.4f, 5.2f)
        val SLEEPING_DIMENSIONS: EntityDimensions = EntityDimensions.changing(5.2f, 1.5f)
        private val POSE_DIMENSIONS: Map<Attack, EntityDimensions> = mutableMapOf(
            Attack.SLEEP to SLEEPING_DIMENSIONS
        )

        private val ATTACK = DataTracker.registerData(Snorlax::class.java, NetworkManager.ATTACK)
        private val IS_DEBUG = DataTracker.registerData(Snorlax::class.java, TrackedDataHandlerRegistry.BOOLEAN)
    }


    override fun tick() {
        super.tick()
        if (task?.isFinished == true) {
            if (isDebug) {
                task?.onDisable()
            } else {
                attack = task!!.nextTask()
            }
        }
    }

    override fun mobTick() {
        super.mobTick()
        bossBar.percent = this.health / this.maxHealth
    }

    override fun initDataTracker() {
        super.initDataTracker()
        dataTracker.startTracking(ATTACK, Attack.IDLE)
        dataTracker.startTracking(IS_DEBUG, false)
    }

    override fun readCustomDataFromNbt(nbt: NbtCompound) {
        super.readCustomDataFromNbt(nbt)
        this.isDebug = nbt.getBoolean("Debug")
    }

    override fun writeCustomDataToNbt(nbt: NbtCompound) {
        super.writeCustomDataToNbt(nbt)
        if (this.isDebug) {
            nbt.putBoolean("Debug", this.isDebug)
        }
    }

    override fun onTrackedDataSet(data: TrackedData<*>?) {
        super.onTrackedDataSet(data)
        if (data?.equals(ATTACK) == true) {
            //TODO ich weiß nicht ob das mal probleme macht
            //das ist dafür da dass auf dem client der task gesycned ist und die hitbox geupdatet wird nach x sekunden z.b. beim bellytask
            //attack = attack
            calculateDimensions()
        }
    }

    override fun getTarget(): LivingEntity? {
        return world.getClosestPlayer(this, 40.0)
    }

    override fun isFireImmune(): Boolean = true
    override fun doesRenderOnFire(): Boolean = false
    override fun handleFallDamage(fallDistance: Float, damageMultiplier: Float, damageSource: DamageSource?) = false
    override fun createNavigation(world: World): EntityNavigation = SnorlaxNavigation(this, world)
    private val dimension: EntityDimensions get() = getDimensions(EntityPose.STANDING)
    private val EntityAttribute.instance: EntityAttributeInstance? get() = attributes.getCustomInstance(this)

    override fun onStartedTrackingBy(player: ServerPlayerEntity) {
        super.onStartedTrackingBy(player)
        bossBar.addPlayer(player)
    }

    override fun onStoppedTrackingBy(player: ServerPlayerEntity) {
        super.onStoppedTrackingBy(player)
        bossBar.removePlayer(player)
    }

    private fun attack(entity: LivingEntity) {
        this.tryAttack(entity)
        val player = entity as? PlayerEntity ?: return
        when (weightedCollection {
            80.0 to "SHIELD"
            10.0 to "ICE"
            10.0 to "FIRE"
        }.next()) {
            "SHIELD" -> if (Random.nextBoolean()) shieldBreaker(player)
            "ICE" -> icePunch(player)
            "FIRE" -> firePunch(player)
        }
    }

    private fun firePunch(player: PlayerEntity) {
        if (player.isBlocking) return
        if (!player.isFrozen) {
            player.setOnFireFor(Random.nextInt(3, 7))
        }
    }

    private fun icePunch(player: PlayerEntity) {
        if (player.isBlocking) return
        if (!player.isOnFire) {
            player.frozenTicks = Random.nextInt(3, 7) * 20
        }
    }

    private fun shieldBreaker(player: PlayerEntity) {
        val item = if (player.isUsingItem) player.activeItem else ItemStack.EMPTY
        if (item.isOf(Items.SHIELD)) {
            player.itemCooldownManager.set(Items.SHIELD, 100)
            player.clearActiveItem()
            world.sendEntityStatus(player, EntityStatuses.BREAK_SHIELD)
        }
    }

    private fun tryAttackWithShieldBreak(entity: Entity) {
        tryAttack(entity)
        if (entity is PlayerEntity) {
            shieldBreaker(entity)
        }
    }

    sealed class Task(val name: String) {
        var jobs = mutableListOf<Job>()
        var isFinished = false
        abstract fun onEnable()
        open fun onDisable() = jobs.forEach(Job::cancel)
        open fun nextTask(): Attack = Attack.IDLE
    }

    override fun calculateBoundingBox(): Box {
        return if (attack == Attack.SLEEP) {
            dimension.getBoxAt(pos.subtract(directionVector.normalize().multiply(2.0)))
        } else if (attack == Attack.BELLY_FLOP) {
            if (customHitBox != null) {
                //TODO junge hier ist diese kack rote eyepos hitbox in der luft aber bei sleep nicht man zukunftsmax kümmer dich drum was soll die scheiße
                dimension.getBoxAt(pos.add(directionVector.normalize().multiply(2.0)))
            } else {
                dimension.getBoxAt(pos)
            }
        } else {
            dimension.getBoxAt(pos)
        }
    }

    inner class SleepTask : Task("Sleep") {
        override fun onEnable() {
            EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE.instance?.baseValue = KNOCKBACK_RESISTANCE_BASE.times(2)
            sound(SoundManager.SLEEPING, 0.14f, 1f)
            Attacks.sleeping(this@Snorlax, 7)
            mcCoroutineTask(delay = 7.seconds) { isFinished = true }
        }

        override fun onDisable() {
            super.onDisable()
            EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE.instance?.baseValue = KNOCKBACK_RESISTANCE_BASE
        }

        override fun nextTask(): Attack {
            return weightedCollection {
                80.0 to Attack.CHECK_TARGET
                20.0 to Attack.RUN
            }.next()
        }
    }

    inner class JumpTask : Task("Jump") {
        override fun onEnable() {
            sound(SoundManager.JUMP, 0.9f, 0.9f)
            modifyVelocity(0, Random.nextDouble(1.0, 2.0), 0)
            jobs += infiniteMcCoroutineTask(delay = 5.ticks) {
                val isGrounded = isOnGround
                if (isGrounded) {
                    mcCoroutineTask(delay = 2.seconds) { isFinished = true }
                    val radius = Random.nextInt(12, 50)

                    sound(SoundManager.LANDING, 0.2f + radius / 30f, 1f)

                    world.getOtherEntities(this@Snorlax, Box.from(pos).expand(HIT_DISTANCE.toDouble() * 2))
                        .forEach(::tryAttackWithShieldBreak)

                    Attacks.radialWave(this@Snorlax, radius)
                    this.cancel()
                }
            }
        }

        override fun nextTask(): Attack {
            return weightedCollection {
                90.0 to Attack.RUN
                10.0 to Attack.SLEEP
            }.next()
        }
    }

    override fun playStepSound(pos: BlockPos, state: BlockState) {
        playSound(SoundManager.FOOT_STEP, 1f, 0.6f)
    }

    inner class RunTask : Task("Run") {

        var time = 0

        override fun onEnable() {
            jobs += infiniteMcCoroutineTask {
                time++
                if(time >= 200) {
                    isFinished = true
                } else if (target == null) {
                    isFinished = true
                } else {
                    if (pos.distanceTo(target!!.pos) >= RUN_DISTANCE) {
                        if (navigation.isIdle) {
                            navigation.startMovingTo(target, 2.0)
                        }
                    } else {
                        isFinished = true
                    }
                }
            }
        }

        override fun onDisable() {
            super.onDisable()
            navigation.stop()
        }

        override fun nextTask(): Attack {
            if(time >= 200)
                return Attack.INHALE
            return weightedCollection {
                if (target == null) {
                    100.0 to Attack.CHECK_TARGET
                } else {
                    40.0 to Attack.MULTIPLE_PUNCH
                    30.0 to Attack.PUNCH
                    12.0 to Attack.JUMP
                    12.0 to Attack.BELLY_FLOP
                    5.0 to Attack.SHAKING
                    1.0 to Attack.BEAM
                }
            }.next()
        }
    }

    inner class CheckTargetTask : Task("CheckTarget") {
        private val checkingDuration = Random.nextInt(1, 5)

        override fun onEnable() {
            jobs += infiniteMcCoroutineTask(period = 20.ticks) {
                sound(SoundManager.SEARCHING_LEFT, 1f, 1f)
            }

            mcCoroutineTask(delay = checkingDuration.seconds) {
                if (target != null) {
                    lookAt(EntityAnchorArgumentType.EntityAnchor.EYES, target!!.pos)
                    val pos = eyePos.add(0.0, 2.5, 0.0)
                    (world as? ServerWorld?)?.spawnParticlesForcefully(
                        ParticleManager.EXCLAMATION_MARK,
                        pos.x,
                        pos.y,
                        pos.z,
                        0,
                        0.0,
                        0.0,
                        0.0,
                        0.0
                    )
                    sound(SoundManager.EXCLAMATION_MARK, 1f, 1f)
                }
                isFinished = true
            }
        }

        override fun nextTask(): Attack {
            return weightedCollection {
                if (target == null) {
                    100.0 to Attack.IDLE
                } else {
                    70.0 to Attack.RUN
                    if (health <= maxHealth / 2) {
                        30.0 to Attack.SLEEP
                    } else {
                        10.0 to Attack.SLEEP
                    }
                    20.0 to Attack.BEAM
                }
            }.next()
        }
    }

    inner class IdleTargetTask : Task("Idle") {
        override fun onEnable() {
            mcCoroutineTask(delay = Random.nextInt(5, 15).seconds) {
                isFinished = true
            }
            jobs += infiniteMcCoroutineTask {
                if (attacker != null) {
                    target = attacker
                    isFinished = true
                }
            }
        }

        override fun nextTask(): Attack {
            return weightedCollection {
                if (target != null) {
                    100.0 to Attack.RUN
                } else {
                    80.0 to Attack.CHECK_TARGET
                    20.0 to Attack.SLEEP
                }
            }.next()
        }
    }

    inner class ShakingTask : Task("Shaking") {
        private val shakeDuration = 2.seconds
        private var pausePlayer: ILivingEntity? = null
        private var modifiedPlayer: ModifiedPlayer? = null

        override fun onEnable() {
            if (target != null) {
                pausePlayer = target as? ILivingEntity?
                modifiedPlayer = target as? ModifiedPlayer?
                val player = target as? PlayerEntity?

                val direction = directionVector
                val eyePos = eyePos.add(direction.multiply(2.0))
                target?.teleport(eyePos.x, eyePos.y - 1.9, eyePos.z)
                pausePlayer?.pause()

                mcCoroutineTask(delay = 13.ticks) {
                    modifiedPlayer?.setShaky(true)
                    sound(SoundManager.SHAKING, 1f, 1f)

                    jobs += infiniteMcCoroutineTask(period = 1.ticks) {
                        (player as? ServerPlayerEntity?)?.apply {
                            BOOM_SHAKE_PACKET.send(CameraShaker.BoomShake(.30, .0, .5), this)
                        }
                    }

                    jobs += infiniteMcCoroutineTask(period = 7.ticks) {
                        tryAttack(target)
                        repeat(Random.nextInt(1, 3)) {
                            val (item, slot) = player?.randomMainInvItem ?: return@repeat
                            mcCoroutineTask(delay = it.ticks) {
                                world?.playSound(
                                    null,
                                    player.blockPos,
                                    SoundEvents.ITEM_BUNDLE_DROP_CONTENTS,
                                    SoundCategory.NEUTRAL,
                                    1f,
                                    1f
                                )
                                player.inventory.main[slot] = ItemStack.EMPTY
                                player.dropItem(item, true, false)
                            }
                        }
                    }
                }

                mcCoroutineTask(delay = shakeDuration) { isFinished = true }
            } else {
                isFinished = true
            }
        }

        override fun onDisable() {
            super.onDisable()
            pausePlayer?.unpause()
            modifiedPlayer?.setShaky(false)
        }

        override fun nextTask(): Attack {
            return weightedCollection {
                if (target != null) {
                    75.0 to Attack.PUNCH
                    20.0 to Attack.JUMP
                    5.0 to Attack.SHAKING
                } else {
                    100.0 to Attack.CHECK_TARGET
                }
            }.next()
        }
    }

    inner class MultiplePunchTask : Task("MultiplePunch") {
        private val radius = 15.0

        override fun onEnable() {
            val job = mcCoroutineTask(howOften = Random.nextLong(2, 5), period = 25.ticks) {
                NetworkManager.FORCE_ANIMATION_RESET.sendToAll(UUIDWrapper(uuid))
                mcCoroutineTask(delay = 5.ticks) {
                    val hitRadius = Box.of(pos, radius, radius, radius)
                    if (target == null) {
                        isFinished = true
                    } else {
                        val toHit = world.getOtherEntities(this@Snorlax, hitRadius)
                            .filter(::canSee)
                            .filterIsInstance<LivingEntity>()
                            .toMutableList()
                        if (distanceTo(target) < HIT_DISTANCE) {
                            if (toHit.none { it.uuid.equals(target?.uuid) }) {
                                toHit.add(target!!)
                            }
                        }
                        toHit.forEach(::attack)
                    }
                }
            }
            jobs += job
            jobs += infiniteMcCoroutineTask {
                if (job.isCancelled || job.isCompleted) isFinished = true
            }
        }

        override fun nextTask(): Attack {
            return weightedCollection {
                20.0 to Attack.SHAKING
                30.0 to Attack.JUMP
                25.0 to Attack.BELLY_FLOP
                if (health <= maxHealth /2) {
                    40.0 to Attack.SLEEP
                } else {
                    20.0 to Attack.SLEEP
                }
                7.0 to Attack.BEAM
            }.next()
        }
    }

    inner class PunchTask : Task("Punch") {
        private val radius = 15.0
        private var distanceFlag = false
        override fun onEnable() {
            NetworkManager.FORCE_ANIMATION_RESET.sendToAll(UUIDWrapper(uuid))

            mcCoroutineTask(delay = 5.ticks) {
                val hitRadius = Box.of(pos, radius, radius, radius)
                if (target == null) {
                    isFinished = true
                } else {
                    if (distanceTo(target) > HIT_DISTANCE) {
                        isFinished = true
                        distanceFlag = true
                    } else {
                        val toHit = world.getOtherEntities(this@Snorlax, hitRadius)
                            .filter(::canSee)
                            .filterIsInstance<LivingEntity>()
                            .toMutableList()
                        if (toHit.none { it.uuid.equals(target?.uuid) }) {
                            toHit.add(target!!)
                        }
                        toHit.forEach(::attack)
                        mcCoroutineTask(delay = 20.ticks) { isFinished = true }
                    }
                }
            }
        }

        override fun nextTask(): Attack {
            return weightedCollection {
                if (distanceFlag) {
                    90.0 to Attack.RUN
                    10.0 to Attack.BELLY_FLOP
                } else {
                    40.0 to Attack.PUNCH
                    20.0 to Attack.RUN
                    10.0 to Attack.BELLY_FLOP
                    10.0 to Attack.JUMP
                    10.0 to Attack.SLEEP
                    5.0 to Attack.BEAM
                }
            }.next()
        }
    }

    inner class BeamTask : Task("Beam") {
        private val prepareTime = 3.seconds
        private var isPreparing = true

        override fun onEnable() {
            lookAtEntity(target, 90f, 90f)

            sound(SoundManager.HYPERBEAM, 5f, 1f)

            jobs += infiniteMcCoroutineTask {
                if (isPreparing) {
                    lookAt(EntityAnchorArgumentType.EntityAnchor.EYES, target!!.pos)
                    val pos = eyePos.add(directionVector.normalize().multiply(3.0))

                    (world as? ServerWorld?)?.spawnParticles(
                        ParticleTypes.SONIC_BOOM,
                        pos.x,
                        pos.y,
                        pos.z,
                        1,
                        0.0,
                        0.0,
                        0.0,
                        0.0
                    )
                }
            }

            mcCoroutineTask(delay = prepareTime) {
                isPreparing = false
                lookAt(EntityAnchorArgumentType.EntityAnchor.EYES, target!!.pos)
                Attacks.hyperBeam(this@Snorlax)
            }

            mcCoroutineTask(delay = Random.nextInt(5, 10).seconds) {
                isFinished = true
            }
        }

        override fun nextTask(): Attack {
            return weightedCollection {
                48.0 to Attack.CHECK_TARGET
                48.0 to Attack.RUN
                4.0 to Attack.SLEEP
            }.next()
        }
    }
    inner class InhaleTask : Task("Inhale") {
        private val prepareTime = 1.seconds
        private var isPreparing = true

        override fun onEnable() {
            lookAtEntity(target, 90f, 90f)

            sound(SoundManager.INHALE, 5f, 1f)

            jobs += infiniteMcCoroutineTask {
                if (isPreparing) {
                    lookAt(EntityAnchorArgumentType.EntityAnchor.EYES, target!!.pos)
                    val pos = eyePos.add(directionVector.normalize().multiply(3.0))

                    (world as? ServerWorld?)?.spawnParticles(
                        ParticleTypes.CLOUD,
                        pos.x,
                        pos.y,
                        pos.z,
                        1,
                        0.0,
                        0.0,
                        0.0,
                        0.0
                    )
                }
            }

            mcCoroutineTask(delay = prepareTime) {
                isPreparing = false
                lookAt(EntityAnchorArgumentType.EntityAnchor.EYES, target!!.pos)
                Attacks.inhale(this@Snorlax, target = target!!)
            }

            mcCoroutineTask(delay = Random.nextInt(5, 10).seconds) {
                isFinished = true
            }
        }

        override fun nextTask(): Attack {
            // Er muss schlafen, weil seine ganze Luft raus ist und keine Energie mehr hat
            return Attack.SLEEP
            /*
            return weightedCollection {
                48.0 to Attack.CHECK_TARGET
                48.0 to Attack.RUN
                4.0 to Attack.SLEEP
            }.next()
            */
        }
    }

    inner class BellyFlopTask : Task("BellyFlop") {
        override fun onEnable() {
            if (target == null) {
                isFinished = true
            } else {
                val from = pos
                val to = target!!.pos //TODO nullcheck
                val direction = to.subtract(from)
                modifyVelocity(direction.normalize().multiply(1.2, 0.0, 1.2))
                modifyVelocity(0, Random.nextDouble(1.0, 1.5), 0)
                sound(SoundManager.JUMP, 0.4f, 1f)

                mcCoroutineTask(delay = 560.milliseconds) {
                    NetworkManager.SET_CUSTOM_HIT_BOX_PACKET.sendToAll(CustomHitBox(uuid, SLEEPING_DIMENSIONS))
                }

                jobs += infiniteMcCoroutineTask(delay = 5.ticks) {
                    val isGrounded = isOnGround

                    if (isGrounded) {
                        mcCoroutineTask(delay = 1.seconds) { isFinished = true }
                        world.getEntitiesByClass(PlayerEntity::class.java, Box.of(pos, 14.0, 5.0, 14.0)) { true }
                            .forEach { player ->
                                world.playSound(
                                    null,
                                    player.blockPos,
                                    SoundEvents.ENTITY_PUFFER_FISH_BLOW_OUT,
                                    SoundCategory.PLAYERS
                                )
                                (player as ModifiedPlayer).setFlat(true)
                                tryAttackWithShieldBreak(player)
                                mcCoroutineTask(delay = Random.nextInt(5, 10).seconds) {
                                    (player as ModifiedPlayer).setFlat(false)
                                    world.playSound(
                                        null,
                                        player.blockPos,
                                        SoundEvents.ENTITY_PUFFER_FISH_BLOW_UP,
                                        SoundCategory.PLAYERS
                                    )
                                }
                            }
                        this.cancel()
                    }
                }
            }
        }

        override fun onDisable() {
            super.onDisable()
            NetworkManager.REMOVE_CUSTOM_HIT_BOX_PACKET.sendToAll(UUIDWrapper(uuid))
        }

        override fun nextTask(): Attack {
            return weightedCollection {
                80.0 to Attack.RUN
                18.0 to Attack.CHECK_TARGET
                2.0 to Attack.SLEEP
            }.next()
        }
    }

    private fun sound(soundEvent: SoundEvent, volume: Float, pitch: Float) {
        world.playSoundFromEntity(null, this, soundEvent, SoundCategory.HOSTILE, volume, pitch)
    }

    //TODO Sleeping hitbox
    override fun getDimensions(pose: EntityPose): EntityDimensions {
        return customHitBox ?: POSE_DIMENSIONS.getOrDefault(attack, STANDING_DIMENSIONS)
    }

    override fun registerControllers(controller: AnimatableManager.ControllerRegistrar) {
        controller.add(AnimationController(this, "controller", 0) {

            if (forceAnimationReset) {
                it.controller.forceAnimationReset()
                forceAnimationReset = false
            }

            it.controller.setAnimation(attack.animation)
            return@AnimationController PlayState.CONTINUE
        }.setParticleKeyframeHandler { })
    }

    override fun getAnimatableInstanceCache(): AnimatableInstanceCache = factory
}

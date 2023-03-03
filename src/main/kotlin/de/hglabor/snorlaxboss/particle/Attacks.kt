package de.hglabor.snorlaxboss.particle

import de.hglabor.snorlaxboss.entity.Snorlax
import de.hglabor.snorlaxboss.network.NetworkManager.BOOM_SHAKE_PACKET
import de.hglabor.snorlaxboss.render.camera.CameraShaker
import net.minecraft.block.AbstractFireBlock
import net.minecraft.block.Block
import net.minecraft.entity.Entity
import net.minecraft.entity.FallingBlockEntity
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.effect.StatusEffectInstance
import net.minecraft.entity.effect.StatusEffects
import net.minecraft.particle.DustParticleEffect
import net.minecraft.particle.ParticleEffect
import net.minecraft.particle.ParticleTypes
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.Box
import net.minecraft.util.math.Direction
import net.minecraft.util.math.Vec3d
import net.minecraft.util.math.Vec3i
import net.silkmc.silk.core.entity.directionVector
import net.silkmc.silk.core.entity.modifyVelocity
import net.silkmc.silk.core.kotlin.ticks
import net.silkmc.silk.core.math.geometry.circlePositionSet
import net.silkmc.silk.core.math.geometry.filledSpherePositionSet
import net.silkmc.silk.core.task.infiniteMcCoroutineTask
import net.silkmc.silk.core.task.mcCoroutineTask
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds


object Attacks {
    fun radialWave(entity: LivingEntity, radius: Int) {
        val vec3i = Vec3i(entity.blockX, entity.blockY - 1, entity.blockZ)
        val world = entity.world
        val hitRadius = 4.0

        world.getOtherEntities(entity, Box.from(entity.pos).expand(radius * 2.0))
            .filterIsInstance<ServerPlayerEntity>()
            .forEach {
                val distanceTo = entity.distanceTo(it)
                val magnitude = 0.1.coerceAtLeast((radius - distanceTo) / 8.0)
                BOOM_SHAKE_PACKET.send(CameraShaker.BoomShake(magnitude, .0, .5), it)
            }

        repeat(radius) { counter ->
            if (counter % 2 == 0) return@repeat
            mcCoroutineTask(delay = counter.ticks) {
                val fallingBlocks = mutableListOf<FallingBlockEntity>()
                vec3i.circlePositionSet(counter).forEach { pos ->
                    val fallingBlockEntity = FallingBlockEntity.spawnFromBlock(world, pos, world.getBlockState(pos))
                    fallingBlocks += fallingBlockEntity
                    fallingBlockEntity.modifyVelocity(0, 0.5, 0)
                }
                val affectedEntities = mutableSetOf<Entity>()
                for (block in fallingBlocks) {
                    affectedEntities.addAll(world.getEntitiesByClass(
                        Entity::class.java, Box.of(block.pos, hitRadius, hitRadius, hitRadius)
                    ) { entity -> return@getEntitiesByClass entity.isOnGround })
                }
                affectedEntities.removeIf { it is Snorlax }
                affectedEntities.forEach {
                    it.modifyVelocity(0, 1, 0)
                }
            }
        }
    }

    fun hyperBeam(
        livingEntity: LivingEntity,
        particle: ParticleEffect = ParticleTypes.SONIC_BOOM,
        withFire: Boolean = true,
        duration: Duration = 4.seconds
    ) {
        var eyePos = livingEntity.eyePos
        val dir = livingEntity.directionVector.normalize().multiply(1.0)
        val world = livingEntity.world as ServerWorld
        val positions = mutableSetOf<Vec3d>()

        fun ParticleEffect.spawn(pos: Vec3d) {
            world.spawnParticles(
                this,
                pos.x,
                pos.y,
                pos.z,
                1,
                (1 / 4.0f).toDouble(),
                (1 / 4.0f).toDouble(),
                (1 / 4.0f).toDouble(),
                0.0
            )

            Vec3i(pos.x, pos.y, pos.z).filledSpherePositionSet(3).forEach {
                world.breakBlock(it, true, livingEntity)
            }

            if (withFire) {
                Vec3i(pos.x, pos.y, pos.z).filledSpherePositionSet(2).forEach { pos ->
                    Direction.values().forEach { direction ->
                        val offset = pos.offset(direction)
                        if (AbstractFireBlock.canPlaceAt(world, offset, direction)) {
                            val blockState2 = AbstractFireBlock.getState(world, offset)
                            if (Random.nextBoolean()) {
                                world.setBlockState(
                                    offset, blockState2, Block.NOTIFY_ALL or Block.REDRAW_ON_MAIN_THREAD
                                )
                            }
                        }
                    }
                }
            }
        }

        val job = infiniteMcCoroutineTask(period = 1.ticks) {
            particle.spawn(eyePos)
            positions.forEachIndexed { index, vec3d ->
                mcCoroutineTask(delay = index.ticks) {
                    particle.spawn(vec3d)
                }
            }

            eyePos = eyePos.add(dir)
            positions += eyePos
        }

        mcCoroutineTask(delay = duration) { job.cancel() }
    }

    fun sleeping(livingEntity: LivingEntity, durationInSeconds: Long) {
        val world = livingEntity.world as ServerWorld
        livingEntity.addStatusEffect(
            StatusEffectInstance(
                StatusEffects.REGENERATION, (durationInSeconds * 20).toInt(), 7, false, false
            )
        )
        livingEntity.addStatusEffect(
            StatusEffectInstance(
                StatusEffects.RESISTANCE, (durationInSeconds * 20).toInt(), 4, false, false
            )
        )


        mcCoroutineTask(howOften = durationInSeconds, delay = 5.ticks, period = 1.seconds) {
            var yOffset = 0.1

            val direction = livingEntity.directionVector
            val particlePos = livingEntity.eyePos.add(0.0, 0.5, 0.0).subtract(direction.multiply(4.5))

            repeat(3) { count ->
                mcCoroutineTask(delay = count.ticks * 2) {
                    yOffset += 0.6
                    val particle = when (count) {
                        0 -> ParticleManager.SLEEP
                        1 -> ParticleManager.SLEEP_MIDDLE
                        2 -> ParticleManager.SLEEP_BIG
                        else -> ParticleManager.SLEEP
                    }
                    world.spawnParticles(
                        particle,
                        particlePos.x + yOffset,
                        particlePos.y + yOffset,
                        particlePos.z,
                        0,
                        0.0,
                        0.0,
                        0.0,
                        0.0
                    )
                }
            }
        }
    }

    fun beam(player: ServerPlayerEntity, radius: Double, length: Int) {
        // Number of points on each circle to show a particle
        val circlePoints = 100

        // Maximum radius before shrinking again.
        var eyePos = player.eyePos
        val world: ServerWorld = player.world as ServerWorld

        // Get the player's looking direction and multiply it by 0.5
        // 0.5 is the number of blocks each new ring will be away from the previous ring
        val dir = player.directionVector.normalize().multiply(0.1)
        val pitch =
            (player.pitch + 90.0f) * 0.017453292f // Need these in radians, not degrees or the circle flattens out sometimes
        val yaw = -player.yaw * 0.017453292f // Need these in radians, not degrees or the circle flattens out sometimes
        val increment = 2 * Math.PI / circlePoints

        // Max beam length
        repeat(length) {
            // This calculates the radius for the current circle/ring in the pattern
            for (i in 0 until circlePoints) {
                val angle = i * increment // Angle on the circle
                val x = radius * kotlin.math.cos(angle)
                val z = radius * kotlin.math.sin(angle)
                var vec = Vec3d(x, 0.0, z)
                vec = vec.rotateX(pitch)
                vec = vec.rotateY(yaw)
                val pos = eyePos.add(vec)
                world.spawnParticles(
                    DustParticleEffect.DEFAULT, pos.x, pos.y, pos.z, 0, 0.0, 0.0, 0.0, 0.0
                ) // Reminder to self - the "data" option for a (particle, location, data) is speed, not count!!
            }
            eyePos = eyePos.add(dir)
        }
    }
}

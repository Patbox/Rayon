package dev.lazurite.rayon.entity.impl.event;

import com.jme3.bounding.BoundingBox;
import com.jme3.math.Vector3f;
import dev.lazurite.rayon.core.impl.RayonCore;
import dev.lazurite.rayon.core.impl.bullet.collision.space.MinecraftSpace;
import dev.lazurite.rayon.core.impl.bullet.collision.space.supplier.player.ClientPlayerSupplier;
import dev.lazurite.rayon.core.impl.bullet.math.Convert;
import dev.lazurite.rayon.core.impl.bullet.thread.PhysicsThread;
import dev.lazurite.rayon.entity.api.EntityPhysicsElement;
import dev.lazurite.rayon.entity.impl.RayonEntity;
import dev.lazurite.rayon.entity.impl.collision.body.EntityRigidBody;
import dev.lazurite.rayon.entity.impl.collision.space.generator.EntityCollisionGenerator;
import dev.lazurite.toolbox.api.math.QuaternionHelper;
import dev.lazurite.toolbox.api.math.VectorHelper;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.EntityLeaveWorldEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = RayonCore.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class ClientEventHandler {
    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(RayonEntity.MOVEMENT_PACKET, ClientEventHandler::onMovement);
        ClientPlayNetworking.registerGlobalReceiver(RayonEntity.PROPERTIES_PACKET, ClientEventHandler::onProperties);
        ClientEntityEvents.ENTITY_LOAD.register(ClientEventHandler::onLoad);
        ClientEntityEvents.ENTITY_UNLOAD.register(ClientEventHandler::onUnload);
        ClientTickEvents.START_WORLD_TICK.register(ClientEventHandler::onStartLevelTick);
    }

    @SubscribeEvent
    private static void onLoad(EntityJoinWorldEvent event) {
        Entity entity = event.getEntity();
        Level level = event.getWorld();
        if (entity instanceof EntityPhysicsElement element) {
            PhysicsThread.get(level).execute(() ->
                    MinecraftSpace.getOptional(level).ifPresent(space ->
                            space.addCollisionObject(element.getRigidBody())
                    )
            );
        }
    }

    @SubscribeEvent
    private static void onUnload(EntityLeaveWorldEvent event) {
        Entity entity = event.getEntity();
        Level level = event.getWorld();
        if (entity instanceof EntityPhysicsElement element) {
            PhysicsThread.get(level).execute(() ->
                MinecraftSpace.getOptional(level).ifPresent(space ->
                        space.removeCollisionObject(element.getRigidBody())
                )
            );
       }
    }

    @SubscribeEvent
    private static void onStartLevelTick(TickEvent.WorldTickEvent event) {
        if(event.phase != TickEvent.Phase.START)return;
        Level level = event.world;
        final var space = MinecraftSpace.get(level);
        EntityCollisionGenerator.applyEntityCollisions(space);

        for (var rigidBody : space.getRigidBodiesByClass(EntityRigidBody.class)) {
            /* Movement */
            if (rigidBody.isActive() && rigidBody.isPositionDirty() && ClientPlayerSupplier.get().equals(rigidBody.getPriorityPlayer())) {
                rigidBody.sendMovementPacket();
            }

            /* Set entity position */
            final var element = ((EntityPhysicsElement) rigidBody.getElement());
            final var location = rigidBody.getFrame().getLocation(new Vector3f(), 1.0f);
            final var offset = rigidBody.boundingBox(new BoundingBox()).getYExtent();
            element.asEntity().absMoveTo(location.x, location.y - offset, location.z);
        }
    }

    @SubscribeEvent
    private static void onMovement(Minecraft minecraft, ClientPacketListener handler, FriendlyByteBuf buf, PacketSender sender) {
        final var entityId= buf.readInt();
        final var rotation = QuaternionHelper.fromBuffer(buf);
        final var location = VectorHelper.fromBuffer(buf);
        final var linearVelocity = VectorHelper.fromBuffer(buf);
        final var angularVelocity = VectorHelper.fromBuffer(buf);

        PhysicsThread.getOptional(minecraft).ifPresent(thread -> thread.execute(() -> {
            if (minecraft.player != null) {
                final var level = minecraft.player.getLevel();
                final var entity = level.getEntity(entityId);

                if (entity instanceof EntityPhysicsElement element) {
                    final var rigidBody = element.getRigidBody();
                    rigidBody.setPhysicsRotation(Convert.toBullet(rotation));
                    rigidBody.setPhysicsLocation(Convert.toBullet(location));
                    rigidBody.setLinearVelocity(Convert.toBullet(linearVelocity));
                    rigidBody.setAngularVelocity(Convert.toBullet(angularVelocity));
                    rigidBody.activate();
                }
            }
        }));
    }

    private static void onProperties(Minecraft minecraft, ClientPacketListener handler, FriendlyByteBuf buf, PacketSender sender) {
        final var entityId = buf.readInt();
        final var mass = buf.readFloat();
        final var dragCoefficient = buf.readFloat();
        final var friction = buf.readFloat();
        final var restitution = buf.readFloat();
        final var doTerrainLoading = buf.readBoolean();
        final var priorityPlayer = buf.readUUID();

        PhysicsThread.getOptional(minecraft).ifPresent(thread -> thread.execute(() -> {
            if (minecraft.player != null) {
                final var level = minecraft.player.getLevel();
                final var entity = level.getEntity(entityId);

                if (entity instanceof EntityPhysicsElement element) {
                    final var rigidBody = element.getRigidBody();
                    rigidBody.setMass(mass);
                    rigidBody.setDragCoefficient(dragCoefficient);
                    rigidBody.setFriction(friction);
                    rigidBody.setRestitution(restitution);
                    rigidBody.setDoTerrainLoading(doTerrainLoading);
                    rigidBody.prioritize(rigidBody.getSpace().getLevel().getPlayerByUUID(priorityPlayer));
                }
            }
        }));
    }
}

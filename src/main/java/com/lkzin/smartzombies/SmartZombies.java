package com.lkzin.smartzombies;

import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod(SmartZombies.MODID)
public class SmartZombies {
    public static final String MODID = "smartzombies";

    public SmartZombies() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof Zombie zombie)) return;
        if (zombie.getPersistentData().getBoolean("smartzombies_initialized")) return;

        zombie.getPersistentData().putBoolean("smartzombies_initialized", true);
        zombie.setCanPickUpLoot(true);

        // Slightly better movement and awareness without making them overpowered.
        if (zombie.getAttribute(Attributes.MOVEMENT_SPEED) != null) {
            zombie.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0.245D);
        }

        // Insert the smart behavior before ordinary goals.
        zombie.goalSelector.addGoal(1, new SmartZombieGoal(zombie));
        zombie.targetSelector.addGoal(2,
                new NearestAttackableTargetGoal<>(zombie, Player.class, true));
    }
}
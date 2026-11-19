package ai.ledog;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.phys.Vec3;

/**
 * Externally driven RL body (eval bench, ledog/rl_bench.py): the learner sets one of 9 discrete actions per 0.5 s; this
 * brain executes it at 10 Hz with the same body limits as the other brains (LimitedMoveControl, 7.57 deg / 0.1 s).
 * Actions: 0 stop, 1 walk, 2 trot, 3 walk + bend left, 4 walk + bend right, 5 turn left, 6 turn right, 7 sit, 8 jump.
 */
public class RlBrain implements WolfBrain {
    static final double TURN = 6.0;  // deg per 0.1 s for bends / turns in place (below the body limit)
    public volatile int action;
    public double heading;
    boolean init, jumped, sitting;

    @Override
    public String kind() {
        return "rl";
    }

    @Override
    public void step(ServerLevel level, Wolf wolf) {
        if (!init) {
            heading = wolf.getYRot();
            init = true;
        }
        int a = action;
        if (a != 7 && sitting) {
            wolf.setInSittingPose(false);
            sitting = false;
        }
        switch (a) {
            case 3, 5 -> heading -= TURN;
            case 4, 6 -> heading += TURN;
            default -> {
            }
        }
        double speed = switch (a) {
            case 1, 3, 4 -> 2.9;   // walk, blocks/s
            case 2 -> 4.0;         // trot
            default -> 0.0;
        };
        wolf.setYRot((float) heading);
        wolf.setYBodyRot((float) heading);
        wolf.setYHeadRot((float) heading);
        if (speed > 0) {
            Vec3 f = PlanBrain.fwd(heading);
            wolf.getMoveControl().setWantedPosition(wolf.getX() + f.x * 2, wolf.getY(), wolf.getZ() + f.z * 2,
                    BmBrain.modifierFor(speed));
        } else {
            wolf.getNavigation().stop();
            wolf.getMoveControl().setWantedPosition(wolf.getX(), wolf.getY(), wolf.getZ(), 0.0);
        }
        if (a == 7 && !sitting) {
            wolf.setInSittingPose(true);
            sitting = true;
        }
        if (a == 8 && wolf.onGround()) {
            wolf.getJumpControl().jump();
            jumped = true;
        }
    }

    /** Distance (blocks, up to max) to the first non-walkable cell along a direction (fence, wall, water, step > 1). */
    static double ray(ServerLevel level, Wolf wolf, double yawDeg, double max) {
        Vec3 f = PlanBrain.fwd(yawDeg);
        int fy = (int) Math.floor(wolf.getY());
        for (double d = 0.5; d <= max; d += 0.5) {
            net.minecraft.core.BlockPos p = net.minecraft.core.BlockPos.containing(wolf.getX() + f.x * d, fy, wolf.getZ() + f.z * d);
            var at = level.getBlockState(p);
            var below = level.getBlockState(p.below());
            boolean solid = !at.getCollisionShape(level, p).isEmpty();
            boolean water = !at.getFluidState().isEmpty() || !below.getFluidState().isEmpty();
            boolean hole = below.getCollisionShape(level, p.below()).isEmpty() && below.getFluidState().isEmpty();
            if (solid || water || hole) return d - 0.5;
        }
        return max;
    }
}

package ai.ledog;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.control.MoveControl;

/**
 * Body actuator model (Gate D, journal 21:20): vanilla MoveControl, but the body (entity) yaw may change by at most
 * {@code maxDegPerTick} per tick. 3.785 deg/tick = 7.57 deg per 0.1 s = EgoPet's 99th-percentile camera yaw rate. The
 * rendered camera follows the entity yaw, so this bounds the camera turn rate physically, whatever the navigation layer
 * does (vanilla MoveControl turns up to 90 deg/tick toward block-centre path nodes). Used for OUR controllers only.
 */
public class LimitedMoveControl extends MoveControl {
    private final float maxDegPerTick;

    public LimitedMoveControl(Mob mob, float maxDegPerTick) {
        super(mob);
        this.maxDegPerTick = maxDegPerTick;
    }

    @Override
    public void tick() {
        float before = this.mob.getYRot();
        super.tick();
        float d = Mth.wrapDegrees(this.mob.getYRot() - before);
        if (Math.abs(d) > maxDegPerTick) {
            float y = before + Math.copySign(maxDegPerTick, d);
            this.mob.setYRot(y);
            this.mob.setYBodyRot(y);
        }
    }
}

package ai.ledog;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.wolf.Wolf;

/** A Le Dog wolf controller, stepped at 10 Hz (every 2 server ticks) on the server thread. */
public interface WolfBrain {
    void step(ServerLevel level, Wolf wolf);

    /** Short name for logs / the wolf's name tag. */
    String kind();

    /** Current state for the per-tick telemetry (phase + brain-specific fields). */
    default com.google.gson.JsonObject status() {
        return new com.google.gson.JsonObject();
    }
}

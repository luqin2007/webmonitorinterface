package com.example.webinterface.web.api;

import com.example.webinterface.util.WorldUtil;
import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerLevel;

import static com.example.webinterface.util.JsonUtil.error;
import static com.example.webinterface.util.JsonUtil.ok;

public class WorldApi {

    public static JsonObject worldInfo(String dim) {
        ServerLevel level = WorldUtil.level(dim);
        if (level == null)
            return error(1002, "Dimension not found: " + dim);
        int dayTime = (int) (level.getDayTime() % 24000L);
        JsonObject d = new JsonObject();
        d.addProperty("dimension", level.dimension().location().toString());
        d.addProperty("day_time", dayTime);
        d.addProperty("game_time", level.getGameTime());
        d.addProperty("is_raining", level.isRaining());
        d.addProperty("is_thundering", level.isThundering());
        d.addProperty("rain_level", level.getRainLevel(1.0F));
        d.addProperty("thunder_level", level.getThunderLevel(1.0F));
        d.addProperty("difficulty", level.getDifficulty().getKey());
        d.addProperty("loaded_chunks", level.getChunkSource().getLoadedChunksCount());
        d.addProperty("hardcore", level.getLevelData().isHardcore());
        return ok(d);
    }
}

package slimeknights.tconstruct.library.modifiers.spilling.effects;

import com.google.gson.JsonDeserializer;
import com.google.gson.JsonObject;
import com.google.gson.JsonSerializationContext;
import io.github.fabricators_of_create.porting_lib.fluids.FluidStack;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import slimeknights.tconstruct.TConstruct;
import slimeknights.tconstruct.library.modifiers.spilling.ISpillingEffect;
import slimeknights.tconstruct.library.tools.context.ToolAttackContext;
import slimeknights.tconstruct.library.utils.JsonUtils;

/**
 * Effect to set an entity on fire
 */
public record SetFireSpillingEffect(int time) implements ISpillingEffect {
  public static final ResourceLocation ID = TConstruct.getResource("set_fire");

  @Override
  public void applyEffects(FluidStack fluid, float scale, ToolAttackContext context) {
    context.getTarget().setSecondsOnFire(time);
  }

  @Override
  public JsonObject serialize(JsonSerializationContext context) {
    JsonObject json = JsonUtils.withType(ID);
    json.addProperty("time", time);
    return json;
  }

  /** Loader for this effect */
  public static final JsonDeserializer<SetFireSpillingEffect> LOADER = (element, type, context) ->
    new SetFireSpillingEffect(GsonHelper.getAsInt(element.getAsJsonObject(), "time"));
}

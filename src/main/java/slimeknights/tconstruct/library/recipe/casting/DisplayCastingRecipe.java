package slimeknights.tconstruct.library.recipe.casting;

import io.github.fabricators_of_create.porting_lib.fluids.FluidStack;
import lombok.Data;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;

import java.util.List;

/**
 * Simple implementation of a display casting recipe, generated by certain recipe types
 */
@SuppressWarnings("ClassCanBeRecord")
@Data
public final class DisplayCastingRecipe implements IDisplayableCastingRecipe {
  private final RecipeType<?> type;
  private final List<ItemStack> castItems;
  private final List<FluidStack> fluids;
  private final ItemStack output;
  private final int coolingTime;
  private final boolean consumed;

  @Override
  public boolean hasCast() {
    return !castItems.isEmpty();
  }
}

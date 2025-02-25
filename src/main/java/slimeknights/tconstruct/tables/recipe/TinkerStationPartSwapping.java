package slimeknights.tconstruct.tables.recipe;

import lombok.AllArgsConstructor;
import lombok.Getter;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;
import slimeknights.tconstruct.TConstruct;
import slimeknights.tconstruct.common.TinkerTags;
import slimeknights.tconstruct.library.materials.MaterialRegistry;
import slimeknights.tconstruct.library.materials.definition.IMaterial;
import slimeknights.tconstruct.library.materials.definition.MaterialVariant;
import slimeknights.tconstruct.library.materials.definition.MaterialVariantId;
import slimeknights.tconstruct.library.materials.stats.IMaterialStats;
import slimeknights.tconstruct.library.materials.stats.IRepairableMaterialStats;
import slimeknights.tconstruct.library.modifiers.Modifier;
import slimeknights.tconstruct.library.modifiers.ModifierEntry;
import slimeknights.tconstruct.library.modifiers.TinkerHooks;
import slimeknights.tconstruct.library.recipe.casting.material.MaterialCastingLookup;
import slimeknights.tconstruct.library.recipe.material.MaterialRecipe;
import slimeknights.tconstruct.library.recipe.modifiers.ModifierRecipeLookup;
import slimeknights.tconstruct.library.recipe.tinkerstation.ITinkerStationContainer;
import slimeknights.tconstruct.library.recipe.tinkerstation.ITinkerStationRecipe;
import slimeknights.tconstruct.library.recipe.tinkerstation.ValidatedResult;
import slimeknights.tconstruct.library.tools.definition.PartRequirement;
import slimeknights.tconstruct.library.tools.helper.ToolDamageUtil;
import slimeknights.tconstruct.library.tools.item.IModifiable;
import slimeknights.tconstruct.library.tools.nbt.ToolStack;
import slimeknights.tconstruct.library.tools.part.IToolPart;
import slimeknights.tconstruct.tables.TinkerTables;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.IntStream;

/**
 * Recipe that replaces a tool part with another
 */
@AllArgsConstructor
public class TinkerStationPartSwapping implements ITinkerStationRecipe {
  private static final ValidatedResult TOO_MANY_PARTS = ValidatedResult.failure(TConstruct.makeTranslationKey("recipe", "part_swapping.too_many_parts"));

  @Getter
  protected final ResourceLocation id;

  @Override
  public boolean matches(ITinkerStationContainer inv, Level world) {
    ItemStack tinkerable = inv.getTinkerableStack();
    if (tinkerable.isEmpty() || !tinkerable.is(TinkerTags.Items.MULTIPART_TOOL)|| !(tinkerable.getItem() instanceof IModifiable)) {
      return false;
    }
    // get the list of parts, empty means its not multipart
    List<PartRequirement> parts = ((IModifiable)tinkerable.getItem()).getToolDefinition().getData().getParts();
    if (parts.isEmpty()) {
      return false;
    }

    // we have two concerns on part swapping:
    // part must be valid in the tool, and only up to one part can be swapped at once
    boolean foundItem = false;
    for (int i = 0; i < inv.getInputCount(); i++) {
      ItemStack stack = inv.getInput(i);
      if (!stack.isEmpty()) {
        // too many items
        if (foundItem) {
          return false;
        }
        // part not in list
        Item item = stack.getItem();
        if (!(item instanceof IToolPart) || parts.stream().noneMatch(p -> p.matches(item))) {
          return false;
        }
        foundItem = true;
      }
    }
    return foundItem;
  }

  /** @deprecated Use {@link #assemble(ITinkerStationContainer, RegistryAccess)}  */
  @Deprecated
  @Override
  public ItemStack getResultItem(RegistryAccess registryAccess) {
    return ItemStack.EMPTY;
  }

  @Override
  public ValidatedResult getValidatedResult(ITinkerStationContainer inv, RegistryAccess registryAccess) {
    // copy the tool NBT to ensure the original tool is intact
    ItemStack tinkerable = inv.getTinkerableStack();
    ToolStack tool = ToolStack.from(tinkerable);
    List<PartRequirement> parts = tool.getDefinition().getData().getParts();

    // prevent part swapping on large tools in small tables
    if (parts.size() > inv.getInputCount()) {
      return TOO_MANY_PARTS;
    }

    // actual part swap logic
    for (int i = 0; i < inv.getInputCount(); i++) {
      ItemStack stack = inv.getInput(i);
      if (!stack.isEmpty()) {
        // not tool part, should never happen
        Item item = stack.getItem();
        if (!(item instanceof IToolPart part)) {
          return ValidatedResult.PASS;
        }

        // ensure the part is valid
        MaterialVariantId partVariant = part.getMaterial(stack);
        if (partVariant.equals(IMaterial.UNKNOWN_ID)) {
          return ValidatedResult.PASS;
        }

        // we have a part and its not at this index, find the first copy of this part
        // means slot only matters if a tool uses a part twice
        int index = i;
        if (i >= parts.size() || !parts.get(i).matches(item)) {
          index = IntStream.range(0, parts.size())
                           .filter(pi -> parts.get(pi).matches(item))
                           .findFirst().orElse(-1);
          if (index == -1) {
            return ValidatedResult.PASS;
          }
        }

        // ensure there is a change in the part or we are repairing the tool, note we compare variants so you could swap oak head for birch head
        MaterialVariant toolVariant = tool.getMaterial(index);
        boolean didChange = !toolVariant.sameVariant(partVariant);
        IMaterialStats stats = MaterialRegistry.getInstance().getMaterialStats(partVariant.getId(), part.getStatType()).orElse(null);
        IRepairableMaterialStats repairable = stats instanceof IRepairableMaterialStats r ? r : null;
        if (!didChange && (tool.getDamage() == 0 || repairable == null)) {
          return ValidatedResult.PASS;
        }

        // actual update
        tool = tool.copy();

        // determine which modifiers are going to be removed
        List<Modifier> actuallyRemoved = Collections.emptyList();
        if (didChange) {
          Map<Modifier,Integer> removedTraits = new HashMap<>();
          // start with a map of all modifiers on the old part
          // TODO: this logic looks correct, but I feel like it might be more complicated than needed
          // basically, if the new part has the modifier, its not going to be removed no matter how the levels differ, a set should suffice
          for (ModifierEntry entry : MaterialRegistry.getInstance().getTraits(toolVariant.getId(), part.getStatType())) {
            removedTraits.put(entry.getModifier(), entry.getLevel());
          }
          // subtract any modifiers on the new part
          for (ModifierEntry entry : MaterialRegistry.getInstance().getTraits(partVariant.getId(), part.getStatType())) {
            Modifier modifier = entry.getModifier();
            if (removedTraits.containsKey(modifier)) {
              int value = removedTraits.get(modifier) - entry.getLevel();
              if (value <= 0) {
                removedTraits.remove(modifier);
              } else {
                removedTraits.put(modifier, value);
              }
            }
          }
          // for the remainder, fill a list as we have another hooks to call with them
          actuallyRemoved = new ArrayList<>();
          for (Entry<Modifier,Integer> entry : removedTraits.entrySet()) {
            Modifier modifier = entry.getKey();
            if (tool.getModifierLevel(modifier) <= entry.getValue()) {
              modifier.getHook(TinkerHooks.RAW_DATA).removeRawData(tool, modifier, tool.getRestrictedNBT());
              actuallyRemoved.add(modifier);
            }
          }

          // do the actual part replacement
          tool.replaceMaterial(index, partVariant);
        }

        // if swapping in a new head, repair the tool (assuming the give stats type can repair)
        // ideally we would validate before repairing, but don't want to create the stack before repairing
        if (repairable != null) {
          // must have a registered recipe
          int cost = MaterialCastingLookup.getItemCost(part);
          if (cost > 0) {
            // apply modifier repair boost
            float factor = cost / MaterialRecipe.INGOTS_PER_REPAIR;
            for (ModifierEntry entry : tool.getModifierList()) {
              factor = entry.getHook(TinkerHooks.REPAIR_FACTOR).getRepairFactor(tool, entry, factor);
              if (factor <= 0) {
                break;
              }
            }
            if (factor > 0) {
              ToolDamageUtil.repair(tool, (int)(repairable.getDurability() * factor));
            }
          }
        }

        // ensure no modifier problems after removing
        // first check tool requirements
        ItemStack result = tool.createStack(Math.min(tinkerable.getCount(), shrinkToolSlotBy()));
        ValidatedResult toolValidation = ModifierRecipeLookup.checkRequirements(result, tool);
        if (toolValidation.hasError()) {
          return toolValidation;
        }
        // next, modifier validation
        Component error = tool.tryValidate();
        if (error != null) {
          return ValidatedResult.failure(error);
        }
        // finally, validate removed modifiers
        for (Modifier modifier : actuallyRemoved) {
          error = modifier.getHook(TinkerHooks.REMOVE).onRemoved(tool, modifier);
          if (error != null) {
            return ValidatedResult.failure(error);
          }
        }
        // everything worked, so good to go
        return ValidatedResult.success(result);
      }
    }
    // no item found, should never happen
    return ValidatedResult.PASS;
  }

  @Override
  public RecipeSerializer<?> getSerializer() {
    return TinkerTables.tinkerStationPartSwappingSerializer.get();
  }
}

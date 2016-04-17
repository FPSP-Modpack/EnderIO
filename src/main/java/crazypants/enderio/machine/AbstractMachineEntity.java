package crazypants.enderio.machine;

import info.loenwind.autosave.Reader;
import info.loenwind.autosave.Writer;
import info.loenwind.autosave.annotations.Storable;
import info.loenwind.autosave.annotations.Store;
import info.loenwind.autosave.annotations.Store.StoreFor;
import info.loenwind.autosave.handlers.enderio.HandleIOMode;

import java.util.EnumMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.ISidedInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;
import net.minecraftforge.fml.client.FMLClientHandler;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import com.enderio.core.common.util.BlockCoord;
import com.enderio.core.common.util.InventoryWrapper;
import com.enderio.core.common.util.ItemUtil;

import crazypants.enderio.EnderIO;
import crazypants.enderio.TileEntityEio;
import crazypants.enderio.api.redstone.IRedstoneConnectable;
import crazypants.enderio.capacitor.CapacitorHelper;
import crazypants.enderio.capacitor.ICapacitorData;
import crazypants.enderio.config.Config;
import crazypants.enderio.paint.IPaintable;
import crazypants.enderio.paint.PainterUtil2;
import crazypants.enderio.paint.YetaUtil;
import crazypants.util.ResettingFlag;

@Storable
public abstract class AbstractMachineEntity extends TileEntityEio
    implements ISidedInventory, IMachine, IRedstoneModeControlable, IRedstoneConnectable, IIoConfigurable {

  @Store({ StoreFor.CLIENT, StoreFor.SAVE })
  public EnumFacing facing = EnumFacing.SOUTH;

  // Client sync monitoring
  protected int ticksSinceSync = -1;
  @Store({ StoreFor.CLIENT, StoreFor.SAVE })
  protected ResettingFlag forceClientUpdate = new ResettingFlag();
  protected boolean lastActive;
  protected int ticksSinceActiveChanged = 0;

  @Store
  protected ItemStack[] inventory;
  protected final SlotDefinition slotDefinition;

  @Store
  protected RedstoneControlMode redstoneControlMode;

  @Store(StoreFor.SAVE)
  protected boolean redstoneCheckPassed;

  private boolean redstoneStateDirty = true;

  @Store(handler = HandleIOMode.class)
  protected Map<EnumFacing, IoMode> faceModes;

  private final int[] allSlots;

  protected boolean notifyNeighbours = false;

  @SideOnly(Side.CLIENT)
  private MachineSound sound;

  private final ResourceLocation soundRes;

  public static ResourceLocation getSoundFor(String sound) {
    return sound == null ? null : new ResourceLocation(EnderIO.DOMAIN + ":" + sound);
  }

  public AbstractMachineEntity(SlotDefinition slotDefinition) {
    this.slotDefinition = slotDefinition;

    inventory = new ItemStack[slotDefinition.getNumSlots()];
    redstoneControlMode = RedstoneControlMode.IGNORE;
    soundRes = getSoundFor(getSoundName());

    allSlots = new int[slotDefinition.getNumSlots()];
    for (int i = 0; i < allSlots.length; i++) {
      allSlots[i] = i;
    }
  }

  @Override
  public IoMode toggleIoModeForFace(EnumFacing faceHit) {
    IoMode curMode = getIoMode(faceHit);
    IoMode mode = curMode.next();
    while (!supportsMode(faceHit, mode)) {
      mode = mode.next();
    }
    setIoMode(faceHit, mode);
    return mode;
  }

  @Override
  public boolean supportsMode(EnumFacing faceHit, IoMode mode) {
    return true;
  }

  @Override
  public void setIoMode(EnumFacing faceHit, IoMode mode) {
    if (mode == IoMode.NONE && faceModes == null) {
      return;
    }
    if (faceModes == null) {
      faceModes = new EnumMap<EnumFacing, IoMode>(EnumFacing.class);
    }
    faceModes.put(faceHit, mode);
    forceClientUpdate.set();
    notifyNeighbours = true;

    updateBlock();
  }

  @Override
  public void clearAllIoModes() {
    if (faceModes != null) {
      faceModes = null;
      forceClientUpdate.set();
      notifyNeighbours = true;
      updateBlock();
    }
  }

  @Override
  public IoMode getIoMode(EnumFacing face) {
    if (faceModes == null) {
      return IoMode.NONE;
    }
    IoMode res = faceModes.get(face);
    if (res == null) {
      return IoMode.NONE;
    }
    return res;
  }

  public SlotDefinition getSlotDefinition() {
    return slotDefinition;
  }

  public boolean isValidUpgrade(ItemStack itemstack) {
    for (int i = slotDefinition.getMinUpgradeSlot(); i <= slotDefinition.getMaxUpgradeSlot(); i++) {
      if (isItemValidForSlot(i, itemstack)) {
        return true;
      }
    }
    return false;
  }

  public boolean isValidInput(ItemStack itemstack) {
    for (int i = slotDefinition.getMinInputSlot(); i <= slotDefinition.getMaxInputSlot(); i++) {
      if (isItemValidForSlot(i, itemstack)) {
        return true;
      }
    }
    return false;
  }

  public boolean isValidOutput(ItemStack itemstack) {
    for (int i = slotDefinition.getMinOutputSlot(); i <= slotDefinition.getMaxOutputSlot(); i++) {
      if (isItemValidForSlot(i, itemstack)) {
        return true;
      }
    }
    return false;
  }

  @Override
  public final boolean isItemValidForSlot(int i, ItemStack itemstack) {
    if (slotDefinition.isUpgradeSlot(i)) {
      final ICapacitorData capacitorData = CapacitorHelper.getCapacitorDataFromItemStack(itemstack);
      return itemstack != null && ((itemstack.getItem() == EnderIO.itemBasicCapacitor && itemstack.getItemDamage() > 0) || capacitorData != null); // TODO level
                                                                                                                                                   // check
    }
    return isMachineItemValidForSlot(i, itemstack);
  }

  protected abstract boolean isMachineItemValidForSlot(int i, ItemStack itemstack);

  @Override
  public RedstoneControlMode getRedstoneControlMode() {
    return redstoneControlMode;
  }

  @Override
  public void setRedstoneControlMode(RedstoneControlMode redstoneControlMode) {
    this.redstoneControlMode = redstoneControlMode;
    redstoneStateDirty = true;
    updateBlock();
  }

  public EnumFacing getFacing() {
    return facing;
  }

  public void setFacing(EnumFacing facing) {
    this.facing = facing;
    markDirty();
  }

  public abstract boolean isActive();

  public String getSoundName() {
    return null;
  }

  public boolean hasSound() {
    return getSoundName() != null;
  }

  public float getVolume() {
    return Config.machineSoundVolume;
  }

  public float getPitch() {
    return 1.0f;
  }

  protected boolean shouldPlaySound() {
    return isActive() && !isInvalid();
  }

  @SideOnly(Side.CLIENT)
  private void updateSound() {
    if (Config.machineSoundsEnabled && hasSound()) {
      if (shouldPlaySound()) {
        if (sound == null) {
          sound = new MachineSound(soundRes, pos.getX() + 0.5f, pos.getY() + 0.5f, pos.getZ() + 0.5f, getVolume(), getPitch());
          FMLClientHandler.instance().getClient().getSoundHandler().playSound(sound);
        }
      } else if (sound != null) {
        sound.endPlaying();
        sound = null;
      }
    }
  }

  // --- Process Loop
  // --------------------------------------------------------------------------

  @Override
  public void doUpdate() {
    if (worldObj.isRemote) {
      updateEntityClient();
      return;
    } // else is server, do all logic only on the server

    boolean requiresClientSync = forceClientUpdate.peek();
    boolean prevRedCheck = redstoneCheckPassed;
    if (redstoneStateDirty) {
      redstoneCheckPassed = RedstoneControlMode.isConditionMet(redstoneControlMode, this);
      redstoneStateDirty = false;
    }

    if (shouldDoWorkThisTick(5)) {
      requiresClientSync |= doSideIo();
    }

    requiresClientSync |= prevRedCheck != redstoneCheckPassed;

    requiresClientSync |= processTasks(redstoneCheckPassed);

    if (requiresClientSync) {

      // this will cause 'getPacketDescription()' to be called and its result
      // will be sent to the PacketHandler on the other end of
      // client/server connection
      worldObj.markBlockForUpdate(pos);
      // And this will make sure our current tile entity state is saved
      markDirty();
    }

    if (notifyNeighbours) {
      worldObj.notifyBlockOfStateChange(pos, getBlockType());
      notifyNeighbours = false;
    }

  }

  protected void updateEntityClient() {
    // check if the block on the client needs to update its texture
    if (isActive() != lastActive) {
      ticksSinceActiveChanged++;
      if (ticksSinceActiveChanged > 20 || isActive()) {
        ticksSinceActiveChanged = 0;
        lastActive = isActive();
        forceClientUpdate.set();
      }
    }

    if (hasSound()) {
      updateSound();
    }

    if (forceClientUpdate.read()) {
      worldObj.markBlockForUpdate(pos);
    } else {
      YetaUtil.refresh(this);
    }
  }

  protected boolean doSideIo() {
    if (faceModes == null) {
      return false;
    }

    boolean res = false;
    Set<Entry<EnumFacing, IoMode>> ents = faceModes.entrySet();
    for (Entry<EnumFacing, IoMode> ent : ents) {
      IoMode mode = ent.getValue();
      if (mode.pulls()) {
        res = res | doPull(ent.getKey());
      }
      if (mode.pushes()) {
        res = res | doPush(ent.getKey());
      }
    }
    return res;
  }

  protected boolean doPush(EnumFacing dir) {

    if (slotDefinition.getNumOutputSlots() <= 0) {
      return false;
    }
    if (!shouldDoWorkThisTick(20)) {
      return false;
    }

    BlockCoord loc = getLocation().getLocation(dir);
    TileEntity te = worldObj.getTileEntity(loc.getBlockPos());

    return doPush(dir, te, slotDefinition.minOutputSlot, slotDefinition.maxOutputSlot);
  }

  protected boolean doPush(EnumFacing dir, TileEntity te, int minSlot, int maxSlot) {
    if (te == null) {
      return false;
    }
    for (int i = minSlot; i <= maxSlot; i++) {
      ItemStack item = inventory[i];
      if (item != null) {
        int num = ItemUtil.doInsertItem(te, item, dir.getOpposite());
        if (num > 0) {
          item.stackSize -= num;
          if (item.stackSize <= 0) {
            item = null;
          }
          inventory[i] = item;
          markDirty();
        }
      }
    }
    return false;
  }

  protected boolean doPull(EnumFacing dir) {

    if (slotDefinition.getNumInputSlots() <= 0) {
      return false;
    }
    if (!shouldDoWorkThisTick(20)) {
      return false;
    }

    boolean hasSpace = false;
    for (int slot = slotDefinition.minInputSlot; slot <= slotDefinition.maxInputSlot && !hasSpace; slot++) {
      hasSpace = inventory[slot] == null ? true : inventory[slot].stackSize < Math.min(inventory[slot].getMaxStackSize(), getInventoryStackLimit(slot));
    }
    if (!hasSpace) {
      return false;
    }

    BlockCoord loc = getLocation().getLocation(dir);
    TileEntity te = worldObj.getTileEntity(loc.getBlockPos());
    if (te == null) {
      return false;
    }
    if (!(te instanceof IInventory)) {
      return false;
    }
    ISidedInventory target;
    if (te instanceof ISidedInventory) {
      target = (ISidedInventory) te;
    } else {
      target = new InventoryWrapper((IInventory) te);
    }

    int[] targetSlots = target.getSlotsForFace(dir.getOpposite());
    if (targetSlots == null) {
      return false;
    }

    for (int inputSlot = slotDefinition.minInputSlot; inputSlot <= slotDefinition.maxInputSlot; inputSlot++) {
      if (doPull(inputSlot, target, targetSlots, dir)) {
        return false;
      }
    }
    return false;
  }

  protected boolean doPull(int inputSlot, ISidedInventory target, int[] targetSlots, EnumFacing side) {
    for (int i = 0; i < targetSlots.length; i++) {
      int tSlot = targetSlots[i];
      ItemStack targetStack = target.getStackInSlot(tSlot);
      if (targetStack != null && target.canExtractItem(i, targetStack, side.getOpposite())) {
        int res = ItemUtil.doInsertItem(this, targetStack, side);
        if (res > 0) {
          targetStack = targetStack.copy();
          targetStack.stackSize -= res;
          if (targetStack.stackSize <= 0) {
            targetStack = null;
          }
          target.setInventorySlotContents(tSlot, targetStack);
          return true;
        }
      }
    }
    return false;
  }

  protected abstract boolean processTasks(boolean redstoneCheck);

  // ---- Tile Entity
  // ------------------------------------------------------------------------------

  @Override
  public void invalidate() {
    super.invalidate();
    if (worldObj.isRemote) {
      updateSound();
    }
  }

  @Override
  public void readCustomNBT(NBTTagCompound nbtRoot) {
    super.readCustomNBT(nbtRoot);
    readCommon(nbtRoot);
  }

  /**
   * Read state common to both block and item
   */
  public void readCommon(NBTTagCompound nbtRoot) {
    if (this instanceof IPaintable.IPaintableTileEntity) {
      paintSource = PainterUtil2.readNbt(nbtRoot);
    }
  }

  public void readFromItemStack(ItemStack stack) {
    if (stack == null || stack.getTagCompound() == null) {
      return;
    }
    NBTTagCompound root = stack.getTagCompound();
    Reader.read(StoreFor.ITEM, root, this);
    if (root.hasKey("eio.abstractMachine")) {
      try {
        doingOtherNbt = true;
        readCommon(root);
      } finally {
        doingOtherNbt = false;
      }
    } else if (this instanceof IPaintable.IPaintableTileEntity) {
      paintSource = PainterUtil2.readNbt(root);
    }
    return;
  }

  @Override
  public void writeCustomNBT(NBTTagCompound nbtRoot) {
    super.writeCustomNBT(nbtRoot);
    writeCommon(nbtRoot);
  }

  /**
   * Write state common to both block and item
   */
  public void writeCommon(NBTTagCompound nbtRoot) {
    if (this instanceof IPaintable.IPaintableTileEntity) {
      PainterUtil2.writeNbt(nbtRoot, paintSource);
    }
  }

  public void writeToItemStack(ItemStack stack) {
    if (stack == null) {
      return;
    }
    if (!stack.hasTagCompound()) {
      stack.setTagCompound(new NBTTagCompound());
    }

    NBTTagCompound root = stack.getTagCompound();
    root.setBoolean("eio.abstractMachine", true);
    try {
      doingOtherNbt = true;
      writeCommon(root);
    } finally {
      doingOtherNbt = false;
    }
    Writer.write(StoreFor.ITEM, root, this);

    String name;
    if (stack.hasDisplayName()) {
      name = stack.getDisplayName();
    } else {
      name = EnderIO.lang.localizeExact(stack.getUnlocalizedName() + ".name");
    }
    name += " " + EnderIO.lang.localize("machine.tooltip.configured");
    stack.setStackDisplayName(name);
  }

  // ---- Inventory
  // ------------------------------------------------------------------------------

  @Override
  public boolean isUseableByPlayer(EntityPlayer player) {
    return canPlayerAccess(player);
  }

  @Override
  public int getSizeInventory() {
    return slotDefinition.getNumSlots();
  }

  public int getInventoryStackLimit(int slot) {
    return getInventoryStackLimit();
  }

  @Override
  public int getInventoryStackLimit() {
    return 64;
  }

  @Override
  public ItemStack getStackInSlot(int slot) {
    if (slot < 0 || slot >= inventory.length) {
      return null;
    }
    return inventory[slot];
  }

  @Override
  public ItemStack decrStackSize(int fromSlot, int amount) {
    ItemStack fromStack = inventory[fromSlot];
    if (fromStack == null) {
      return null;
    }
    if (fromStack.stackSize <= amount) {
      inventory[fromSlot] = null;
      return fromStack;
    }
    ItemStack result = new ItemStack(fromStack.getItem(), amount, fromStack.getItemDamage());
    if (fromStack.getTagCompound() != null) {
      result.setTagCompound((NBTTagCompound) fromStack.getTagCompound().copy());
    }
    fromStack.stackSize -= amount;
    return result;
  }

  @Override
  public void setInventorySlotContents(int slot, ItemStack contents) {
    if (contents == null) {
      inventory[slot] = contents;
    } else {
      inventory[slot] = contents.copy();
    }
    if (contents != null && contents.stackSize > getInventoryStackLimit(slot)) {
      contents.stackSize = getInventoryStackLimit(slot);
    }
  }

  @Override
  public void clear() {
    for (int i = 0; i < inventory.length; ++i) {
      inventory[i] = null;
    }
  }

  @Override
  public ItemStack removeStackFromSlot(int index) {
    ItemStack res = inventory[index];
    inventory[index] = null;
    return res;
  }

  @Override
  public int getField(int id) {
    return 0;
  }

  @Override
  public void setField(int id, int value) {
  }

  @Override
  public int getFieldCount() {
    return 0;
  }

  @Override
  public void openInventory(EntityPlayer player) {
  }

  @Override
  public void closeInventory(EntityPlayer player) {
  }

  @Override
  public String getName() {
    return getMachineName();
  }

  @Override
  public boolean hasCustomName() {
    return false;
  }

  @Override
  public IChatComponent getDisplayName() {
    return hasCustomName() ? new ChatComponentText(getName()) : new ChatComponentTranslation(getName(), new Object[0]);
  }

  @Override
  public int[] getSlotsForFace(EnumFacing var1) {
    if (isSideDisabled(var1)) {
      return new int[0];
    }
    return allSlots;
  }

  @Override
  public boolean canInsertItem(int slot, ItemStack itemstack, EnumFacing side) {
    if (isSideDisabled(side) || !slotDefinition.isInputSlot(slot)) {
      return false;
    }
    ItemStack existing = inventory[slot];
    if (existing != null) {
      // no point in checking the recipes if an item is already in the slot
      // worst case we get more of the wrong item - but that doesn't change
      // anything
      return existing.isStackable() && existing.isItemEqual(itemstack);
    }
    // no need to call isItemValidForSlot as upgrade slots are not input slots
    return isMachineItemValidForSlot(slot, itemstack);
  }

  @Override
  public boolean canExtractItem(int slot, ItemStack itemstack, EnumFacing side) {
    if (isSideDisabled(side)) {
      return false;
    }
    if (!slotDefinition.isOutputSlot(slot)) {
      return false;
    }
    return canExtractItem(slot, itemstack);
  }

  protected boolean canExtractItem(int slot, ItemStack itemstack) {
    if (inventory[slot] == null || inventory[slot].stackSize < itemstack.stackSize) {
      return false;
    }
    return itemstack.getItem() == inventory[slot].getItem();
  }

  public boolean isSideDisabled(EnumFacing dir) {
    IoMode mode = getIoMode(dir);
    if (mode == IoMode.DISABLED) {
      return true;
    }
    return false;
  }

  public void onNeighborBlockChange(Block blockId) {
    redstoneStateDirty = true;
  }

  /* IRedstoneConnectable */

  @Override
  public boolean shouldRedstoneConduitConnect(World world, int x, int y, int z, EnumFacing from) {
    return true;
  }
  
  @Override
  public BlockCoord getLocation() {    
    return new BlockCoord(pos);
  }

  // ///////////////////////////////////////////////////////////////////////
  // PAINT START
  // ///////////////////////////////////////////////////////////////////////

  private IBlockState paintSource = null;

  public void setPaintSource(IBlockState paintSource) {
    this.paintSource = paintSource;
    markDirty();
    updateBlock();
  }

  public IBlockState getPaintSource() {
    return paintSource;
  }

  // ///////////////////////////////////////////////////////////////////////
  // PAINT END
  // ///////////////////////////////////////////////////////////////////////

}

package crazypants.enderio.machine.obelisk.aversion;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import com.enderio.core.client.render.BoundingBox;

import crazypants.enderio.ModObject;
import crazypants.enderio.machine.AbstractPowerConsumerEntity;
import crazypants.enderio.machine.SlotDefinition;
import crazypants.enderio.machine.ranged.IRanged;
import crazypants.enderio.machine.ranged.RangeEntity;
import crazypants.util.CapturedMob;

import static crazypants.enderio.capacitor.CapacitorKey.AVERSION_POWER_BUFFER;
import static crazypants.enderio.capacitor.CapacitorKey.AVERSION_POWER_INTAKE;
import static crazypants.enderio.capacitor.CapacitorKey.AVERSION_POWER_USE;
import static crazypants.enderio.capacitor.CapacitorKey.AVERSION_RANGE;

public class TileAversionObelisk extends AbstractPowerConsumerEntity implements IRanged {

  private boolean registered = false;
  private AxisAlignedBB bounds;
  
  private boolean showingRange;
  
  public TileAversionObelisk() {
    super(new SlotDefinition(12, 0), AVERSION_POWER_INTAKE, AVERSION_POWER_BUFFER, AVERSION_POWER_USE);
  }
  
  @Override
  public World getRangeWorldObj() {   
    return getWorld();
  }

  @Override
  @SideOnly(Side.CLIENT)
  public boolean isShowingRange() {
    return showingRange;
  }
  
  @SideOnly(Side.CLIENT)
  public void setShowRange(boolean showRange) {
    if(showingRange == showRange) {
      return;
    }
    showingRange = showRange;
    if(showingRange) {
      worldObj.spawnEntityInWorld(new RangeEntity(this));
    }
  }
 
  @Override
  public void invalidate() {
    super.invalidate();    
    AversionObeliskController.instance.deregisterGuard(this);
    registered = false;
  }
  
  @Override
  public float getRange() {
    return AVERSION_RANGE.getFloat(getCapacitorData());
  }

  @Override
  public void onCapacitorDataChange() {
    super.onCapacitorDataChange();
    bounds = null;
  }

  @Override
  public String getMachineName() {
    return ModObject.blockSpawnGuard.getUnlocalisedName();
  }

  @Override
  protected boolean isMachineItemValidForSlot(int i, ItemStack itemstack) {
    if(!slotDefinition.isInputSlot(i)) {
      return false;
    }
    return CapturedMob.containsSoul(itemstack);
  }

  @Override
  public boolean isActive() {
    return hasPower();
  }

  @Override
  protected boolean processTasks(boolean redstoneCheck) {
    if (bounds == null) {
      BoundingBox bb = new BoundingBox(getLocation());
      bb = bb.scale(getRange() + 0.5f, getRange() + 0.5f, getRange() + 0.5f).translate(0.5f, 0.5f, 0.5f);
      bounds = new AxisAlignedBB(bb.minX, bb.minY, bb.minZ, bb.maxX, bb.maxY, bb.maxZ);
    }
    if (redstoneCheck && hasPower()) {
      if(!registered) {
        AversionObeliskController.instance.registerGuard(this);
        registered = true;
      }
      usePower();
    } 
    return false;    
  }

  protected double usePower() {
    return usePower(getPowerUsePerTick());
  }

  protected int usePower(int wantToUse) {
    int used = Math.min(getEnergyStored(), wantToUse);
    setEnergyStored(Math.max(0, getEnergyStored() - used));
    return used;
  }

  public boolean isSpawnPrevented(EntityLivingBase mob) {
    return redstoneCheckPassed && hasPower() && isMobInRange(mob) && isMobInFilter(mob);
  }
  
  private boolean isMobInRange(EntityLivingBase mob) {
    if (mob == null || bounds == null) {
      return false;
    }    
    //return new Vector3d(mob.posX, mob.posY, mob.posZ).distanceSquared(new Vector3d(xCoord, yCoord, zCoord)) <= rangeSqu;
    return bounds.isVecInside(new Vec3(mob.posX, mob.posY, mob.posZ));
  }

  private boolean isMobInFilter(EntityLivingBase entity) {
    for (int i = slotDefinition.minInputSlot; i <= slotDefinition.maxInputSlot; i++) {
      CapturedMob mob = CapturedMob.create(inventory[i]);
      if (mob != null && mob.isSameType(entity)) {
        return true;
      }
    }
    return false;
  }

}

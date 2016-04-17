package info.loenwind.autosave.handlers.enderio;

import info.loenwind.autosave.Registry;
import info.loenwind.autosave.annotations.Store.StoreFor;
import info.loenwind.autosave.exceptions.NoHandlerFoundException;
import info.loenwind.autosave.handlers.IHandler;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import net.minecraft.nbt.NBTTagCompound;
import crazypants.enderio.machine.IPoweredTask;

public class HandlePoweredTask implements IHandler<IPoweredTask> {

  public HandlePoweredTask() {
  }

  @Override
  public boolean canHandle(Class<?> clazz) {
    return IPoweredTask.class.isAssignableFrom(clazz);
  }

  @Override
  public boolean store(@Nonnull Registry registry, @Nonnull Set<StoreFor> phase, @Nonnull NBTTagCompound nbt, @Nonnull String name, @Nonnull IPoweredTask object)
      throws IllegalArgumentException, IllegalAccessException, InstantiationException, NoHandlerFoundException {
    NBTTagCompound tag = new NBTTagCompound();
    object.writeToNBT(tag);
    tag.setString("class", object.getClass().getName());
    nbt.setTag(name, tag);
    return true;
  }

  @Override
  public IPoweredTask read(@Nonnull Registry registry, @Nonnull Set<StoreFor> phase, @Nonnull NBTTagCompound nbt, @Nonnull String name,
      @Nullable IPoweredTask object) throws IllegalArgumentException, IllegalAccessException, InstantiationException, NoHandlerFoundException {
    if (nbt.hasKey(name)) {
      try {
        NBTTagCompound tag = (NBTTagCompound) nbt.getTag(name);
        String className = tag.getString("class");
        if (className != null && !className.isEmpty()) {
          Class<?> clazz = Class.forName(className);
          if (clazz != null) {
            Method method = clazz.getDeclaredMethod("readFromNBT", NBTTagCompound.class);
            if (method != null) {
              Object object2 = method.invoke(null, tag);
              if (object2 instanceof IPoweredTask) {
                return (IPoweredTask) object2;
              }
            }
          }
        }
      } catch (ClassNotFoundException e) {
        throw new IllegalArgumentException(e);
      } catch (NoSuchMethodException e) {
        throw new IllegalArgumentException(e);
      } catch (SecurityException e) {
        throw new IllegalArgumentException(e);
      } catch (InvocationTargetException e) {
        throw new IllegalArgumentException(e);
      }
    }
    return null;
  }

}

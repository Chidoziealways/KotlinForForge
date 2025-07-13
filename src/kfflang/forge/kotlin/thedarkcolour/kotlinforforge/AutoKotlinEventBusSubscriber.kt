package thedarkcolour.kotlinforforge

import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.eventbus.api.listener.SubscribeEvent
import net.minecraftforge.fml.Logging
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.loading.FMLEnvironment
import net.minecraftforge.forgespi.language.ModFileScanData
import net.minecraftforge.forgespi.language.ModFileScanData.EnumData
import org.objectweb.asm.Type
import java.lang.invoke.MethodHandles
import java.lang.reflect.Method
import java.util.*

/**
 * Automatically registers `object` classes to
 * Kotlin for Forge's event buses.
 *
 * This also allows [Mod.EventBusSubscriber] to be used as a file-wide annotation,
 * registering any top-level functions annotated with @SubscribeEvent to the event bus.
 *
 * Example:
 * ```
 * @file:Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.MOD)
 *
 * package example
 *
 * @SubscribeEvent
 * fun onCommonSetup(event: FMLCommonSetupEvent) {
 *   // registered to mod event bus
 * }
 * ```
 *
 * @see thedarkcolour.kotlinforforge.forge.MOD_BUS
 * @see thedarkcolour.kotlinforforge.forge.FORGE_BUS
 */
public object AutoKotlinEventBusSubscriber {
    // EventBusSubscriber annotation
    private val EVENT_BUS_SUBSCRIBER: Type = Type.getType(Mod.EventBusSubscriber::class.java)
    private val SUBSCRIBE_EVENT: Type = Type.getType(SubscribeEvent::class.java)

    // Legacy EnumHolder
    private val enumHolderGetValue: Method? = try {
        val klass = Class.forName("net.minecraftforge.fml.loading.moddiscovery.ModAnnotation\$EnumHolder")
        klass.getDeclaredMethod("getValue")
    } catch (e: ClassNotFoundException) {
        null
    }

    /**
     * Allows the [Mod.EventBusSubscriber] annotation
     * to target member functions of an `object` class.
     *
     * You **must** be using an `object` class, or the
     * `Mod.EventBusSubscriber` annotation will ignore it.
     *
     * I am against using `Mod.EventBusSubscriber`
     * because it makes it difficult to follow where event
     * listeners are registered. Instead, prefer to directly
     * register event listeners to
     * [thedarkcolour.kotlinforforge.forge.FORGE_BUS]
     * or [thedarkcolour.kotlinforforge.forge.MOD_BUS].
     */
    public fun inject(mod: KotlinModContainer, scanData: ModFileScanData, classLoader: ClassLoader) {
        LOGGER.debug(Logging.LOADING, "Attempting to inject @EventBusSubscriber kotlin objects in to the event bus for ${mod.modId}")

        val data = scanData.annotations.filter { it.annotationData == EVENT_BUS_SUBSCRIBER }

        for (annotationData in data) {
            val annotationMap = annotationData.annotationData
            val sides = getSides(annotationMap)
            val modid = annotationMap.getOrDefault("modid", mod.modId)
            val busTarget = getBusTarget(annotationMap)

            if (mod.modId == modid && FMLEnvironment.dist in sides) {
                val kClass = Class.forName(annotationData.clazz.className, true, classLoader).kotlin

                val instance = try {
                    kClass.objectInstance
                } catch (_: UnsupportedOperationException) {
                    null
                }

                if (instance != null) {
                    try {
                        val lookup = MethodHandles.privateLookupIn(instance::class.java, MethodHandles.lookup())
                        val bus = if (busTarget == Mod.EventBusSubscriber.Bus.FORGE) {
                            busTarget.bus().get()
                        } else {
                            mod.busGroup
                        }
                        LOGGER.debug(LOADING, "Registering Kotlin object: ${annotationData.clazz.className} to $busTarget")

                        bus?.register(lookup, instance)
                    } catch (t: Throwable) {
                        LOGGER.fatal(LOADING, "Failed to register Kotlin object ${annotationData.clazz.className}", t)
                        throw RuntimeException(t)
                    }
                }
            }
        }
    }

    private fun getSides(annotationMap: Map<String, Any>): List<Dist> {
        val sidesHolders = annotationMap["value"] ?: return listOf(Dist.CLIENT, Dist.DEDICATED_SERVER)

        return if (enumHolderGetValue != null) {
                (sidesHolders as List<*>).map { Dist.valueOf(enumHolderGetValue.invoke(it) as String) }
        } else {
                (sidesHolders as List<EnumData>).map { Dist.valueOf(it.value()) }
        }
    }

    private fun getBusTarget(annotationMap: Map<String, Any>): Mod.EventBusSubscriber.Bus {
        val holder = annotationMap["bus"] ?: return Mod.EventBusSubscriber.Bus.FORGE

        return if (enumHolderGetValue != null) {
            Mod.EventBusSubscriber.Bus.valueOf(enumHolderGetValue.invoke(holder) as String)
        } else {
            Mod.EventBusSubscriber.Bus.valueOf((holder as EnumData).value)
        }
    }

    private fun registerTo(any: Any, target: Mod.EventBusSubscriber.Bus, mod: KotlinModContainer) {
        if (target == Mod.EventBusSubscriber.Bus.FORGE) {
            val lookup = MethodHandles.privateLookupIn(any::class.java, MethodHandles.lookup())
            target.bus().get()!!.register(lookup, any)
        } else {
            mod.busGroup.register(MethodHandles.lookup(), any)
        }
    }
}

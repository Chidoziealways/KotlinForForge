package thedarkcolour.kotlinforforge.neoforge

import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.Logging
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.fml.common.Mod
import net.neoforged.fml.javafmlmod.AutomaticEventSubscriber
import net.neoforged.fml.loading.FMLEnvironment
import net.neoforged.fml.loading.modscan.ModAnnotation
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforgespi.language.ModFileScanData
import org.objectweb.asm.Type
import kotlin.reflect.KFunction
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.full.memberFunctions

/**
 * Automatically registers `object` classes to
 * Kotlin for Forge's event buses.
 *
 * This also allows [EventBusSubscriber] to be used as a file-wide annotation,
 * registering any top-level functions annotated with @SubscribeEvent to the event bus.
 *
 * Example:
 * ```
 * @file:Mod.EventBusSubscriber
 *
 * package example
 *
 * @SubscribeEvent
 * fun onCommonSetup(event: FMLCommonSetupEvent) {
 *   // registered to mod event bus
 * }
 * ```
 */
public object AutoKotlinEventBusSubscriber {
    // EventBusSubscriber annotation
    private val EVENT_BUS_SUBSCRIBER: Type = Type.getType(EventBusSubscriber::class.java)
    private val MOD: Type = Type.getType(Mod::class.java)

    /**
     * Allows the [EventBusSubscriber] annotation
     * to target member functions of an `object` class.
     *
     * You **must** be using an `object` class, or the
     * `Mod.EventBusSubscriber` annotation will ignore it.
     *
     * I am against using `Mod.EventBusSubscriber`
     * because it makes it difficult to follow where event
     * listeners are registered. Instead, prefer to directly
     * register event listeners to the forge bus or the mod-specific bus.
     */
    public fun inject(mod: KotlinModContainer, scanData: ModFileScanData?, layer: Module) {
        if (scanData == null) return
        LOGGER.debug(Logging.LOADING, "Attempting to inject @EventBusSubscriber kotlin objects in to the event bus for ${mod.modId}")

        val ebsTargets = scanData.annotations.filter { annotationData ->
            EVENT_BUS_SUBSCRIBER == annotationData.annotationType
        }
        val modids = scanData.annotations.filter { annotationData ->
            MOD == annotationData.annotationType
        }.associate { annotationData ->
            annotationData.clazz.className to annotationData.annotationData.get("value")
        }

        // we only need to worry about cases where NeoForge can't automatically register (object and file)
        for (annotationData in ebsTargets) {
            val sides = AutomaticEventSubscriber.getSides(annotationData.annotationData.get("value"))
            val className = annotationData.clazz.className
            val modid = annotationData.annotationData.getOrDefault("modid", modids.getOrDefault(className, mod.modId))
            val busTargetHolder = annotationData.annotationData.getOrDefault("bus", ModAnnotation.EnumHolder(null, "GAME")) as ModAnnotation.EnumHolder
            val busTarget = busTargetHolder.value

            if (mod.modId == modid && FMLEnvironment.dist in sides) {
                val kClass = Class.forName(annotationData.clazz.className, true, layer.classLoader).kotlin

                var ktObject: Any?

                try {
                    ktObject = kClass.objectInstance
                } catch (unsupported: UnsupportedOperationException) {
                    if (unsupported.message?.contains("file facades") == false) {
                        throw unsupported
                    }
                    ktObject = null
                }

                if (ktObject != null) {
                    try {
                        LOGGER.debug(Logging.LOADING, "Auto-subscribing kotlin object {} to {}", annotationData.annotationType.className, busTarget)

                        val gameListeners = arrayListOf<KFunction<*>>()
                        val modListeners = arrayListOf<KFunction<*>>()

                        for (function in kClass.declaredMemberFunctions) {
                            if (function.annotations.contains(@SubscribeEvent))
                        }

                        registerTo(ktObject, busTarget, mod)
                    } catch (e: Throwable) {
                        LOGGER.fatal(Logging.LOADING, "Failed to load mod class ${annotationData.annotationType} for @EventBusSubscriber annotation", e)
                        throw RuntimeException(e)
                    }
                } else {
                    LOGGER.debug(Logging.LOADING, "Auto-subscribing kotlin file {} to {}", annotationData.annotationType.className, busTarget)
                    registerTo(kClass.java, busTarget, mod)
                }
            }
        }
    }

    private fun registerTo(any: Any, target: String, mod: KotlinModContainer) {
        if (target == "GAME") {
            NeoForge.EVENT_BUS.register(any)
        } else if (target == "MOD") {
            mod.eventBus.register(any)
        }
    }
}

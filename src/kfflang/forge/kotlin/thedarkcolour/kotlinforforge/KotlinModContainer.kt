package thedarkcolour.kotlinforforge

import cpw.mods.jarhandling.SecureJar
import net.minecraftforge.eventbus.EventBusErrorMessage
import net.minecraftforge.eventbus.api.Event
import net.minecraftforge.eventbus.api.IEventListener
import net.minecraftforge.eventbus.api.bus.BusGroup
import net.minecraftforge.fml.Logging
import net.minecraftforge.fml.ModContainer
import net.minecraftforge.fml.ModLoadingException
import net.minecraftforge.fml.ModLoadingStage
import net.minecraftforge.fml.event.IModBusEvent
import net.minecraftforge.forgespi.language.IModInfo
import net.minecraftforge.forgespi.language.ModFileScanData
import java.util.function.Supplier
import java.util.jar.Attributes

/**
 * Kotlin mod container
 */
public class KotlinModContainer(
    info: IModInfo,
    className: String,
    private val scanResults: ModFileScanData,
    gameLayer: ModuleLayer,
) : ModContainer(info) {
    private var modInstance: Any? = null
    internal var busGroup: BusGroup
    private val modClass: Class<*>
    val ctx = KotlinModLoadingContext(this)
    private val implAddExportsOrOpens = Module::class.java.getDeclaredMethod(
        "implAddExportsOrOpens", String::class.java, Module::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType
    ).apply {
        isAccessible = true
    }

    init {
        LOGGER.debug(Logging.LOADING, "Creating KotlinModContainer instance for $className")
        activityMap[ModLoadingStage.CONSTRUCT] = Runnable(::constructMod)
        busGroup = BusGroup.create("modBusFor ${info.getModId()}")
        contextExtension = Supplier {ctx}

        try {
            val moduleName = info.getOwningFile().moduleName()
            val layer = gameLayer.findModule(moduleName).orElseThrow()
            openModules(gameLayer, layer, info.owningFile.file.secureJar)
            modClass = Class.forName(layer, className)
            LOGGER.trace(Logging.LOADING, "Loaded modclass {} with {}", modClass.name, modClass.classLoader)
        } catch (t: Throwable) {
            LOGGER.error(Logging.LOADING, "Failed to load class $className", t)
            throw ModLoadingException(info, ModLoadingStage.CONSTRUCT, "fml.modloading.failedtoloadmodclass", t)
        }
    }

    private fun onEventFailed(iEventBus: BusGroup, event: Event, listeners: Array<IEventListener>, busId: Int, throwable: Throwable) {
        LOGGER.error(EventBusErrorMessage(event, busId, listeners, throwable))
    }

    // Sets modInstance to a new instance of the mod class or the object instance
    private fun constructMod() {
        try {
            LOGGER.trace(Logging.LOADING, "Loading mod instance ${getModId()} of type ${modClass.name}")
            modInstance = modClass.kotlin.objectInstance ?: modClass.getDeclaredConstructor().newInstance()
            LOGGER.trace(Logging.LOADING, "Loaded mod instance ${getModId()} of type ${modClass.name}")
        } catch (throwable: Throwable) {
            LOGGER.error(Logging.LOADING, "Failed to create mod instance. ModID: ${getModId()}, class ${modClass.name}", throwable)
            throw ModLoadingException(modInfo, ModLoadingStage.CONSTRUCT, "fml.modloading.failedtoloadmod", throwable, modClass)
        }

        try {
            LOGGER.trace(Logging.LOADING, "Injecting Automatic Kotlin event subscribers for ${getModId()}")
            // Inject into object EventBusSubscribers
            AutoKotlinEventBusSubscriber.inject(this, scanResults, modClass.classLoader)
            LOGGER.trace(Logging.LOADING, "Completed Automatic Kotlin event subscribers for ${getModId()}")
        } catch (throwable: Throwable) {
            LOGGER.error(Logging.LOADING, "Failed to register Automatic Kotlin subscribers. ModID: ${getModId()}, class ${modClass.name}", throwable)
            throw ModLoadingException(modInfo, ModLoadingStage.CONSTRUCT, "fml.modloading.failedtoloadmod", throwable, modClass)
        }
    }

    override fun matches(mod: Any?): Boolean {
        return mod == modInstance
    }

    override fun getMod(): Any? = modInstance

    fun getModBusGroup(): BusGroup {
        return busGroup
    }

    public override fun <T> acceptEvent(e: T) where T : Event, T : IModBusEvent {
        try {
            LOGGER.trace("Firing event for modid $modId : $e")
            var eventBus = IModBusEvent.getBus(busGroup, e.javaClass)
            eventBus.post(e)
            LOGGER.trace("Fired event for modid $modId : $e")
        } catch (t: Throwable) {
            LOGGER.error("Caught exception during event $e dispatch for modid $modId", t)
            throw ModLoadingException(modInfo, modLoadingStage, "fml.modloading.errorduringevent", t)
        }
    }

    private fun openModules(layer: ModuleLayer, self: Module, jar: SecureJar) {
        val manifest = jar.moduleDataProvider().manifest.mainAttributes
        addOpenOrExports(layer, self, true, manifest)
        addOpenOrExports(layer, self, false, manifest)
    }

    private fun addOpenOrExports(layer: ModuleLayer, self: Module, open: Boolean, attrs: Attributes) {
        val key = if (open) "Add-Opens" else "Add-Exports"
        val entry = attrs.getValue(key) ?: return
        entry.split(" ").forEach { pair ->
            val pts = pair.trim().split("/")
            if (pts.size == 2) {
                val target = layer.findModule(pts[0]).orElse(null)
                if (target != null && target.descriptor.packages().contains(pts[1])) {
                    implAddExportsOrOpens.invoke(target, pts[1], self, open, true)
                }
            }
        }
    }

}

package thedarkcolour.kotlinforforge.common

import net.neoforged.api.distmarker.Dist
import java.util.function.Supplier

/**
 *  This defines a Kotlin Mod Class
 *  Any class found with this annotation applied will be loaded as a Mod. The instance that is loaded will
 *  represent the mod to other Mods in the system. It will be sent various subclasses of [ModLifecycleEvent]
 *  at pre-defined times during the loading of the game.
 *  @author Chidoziealways
 *  @since 6.0.0
 */

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
public annotation class KotlinMod(val value: String) {

    public annotation class KotlinEventBusSubscriber(
        val value: Array<Dist> = [Dist.CLIENT, Dist.DEDICATED_SERVER],
        val modId: String = ""
    ) {
    }
}

package thedarkcolour.kotlinforforge

import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.api.distmarker.OnlyIn
import net.minecraftforge.eventbus.api.bus.BusGroup
import net.minecraftforge.eventbus.api.bus.CancellableEventBus
import net.minecraftforge.eventbus.api.bus.EventBus
import net.minecraftforge.eventbus.api.event.characteristic.Cancellable
import net.minecraftforge.eventbus.api.listener.EventListener
import net.minecraftforge.eventbus.api.listener.ObjBooleanBiConsumer
import net.minecraftforge.eventbus.api.listener.Priority
import net.minecraftforge.eventbus.api.listener.SubscribeEvent
import net.minecraftforge.eventbus.internal.Event
import net.minecraftforge.fml.Logging
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.event.IModBusEvent
import net.minecraftforge.fml.loading.FMLEnvironment
import net.minecraftforge.forgespi.language.ModFileScanData
import net.minecraftforge.forgespi.language.ModFileScanData.EnumData
import org.objectweb.asm.Type
import java.lang.invoke.LambdaMetafactory
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.*
import java.util.function.Consumer
import java.util.function.Predicate

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
    private val MOD_TYPE: Type = Type.getType(Mod::class.java)
    private val ONLY_IN_TYPE: Type = Type.getType(OnlyIn::class.java)

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

        val targets = scanData.annotations.filter { data -> EVENT_BUS_SUBSCRIBER == data.annotationType }.toList()

        val onlyIns: Set<String> = if (FMLEnvironment.production) {
            emptySet()
        } else {
            scanData.annotations
                .asSequence()
                .filter { it.annotationType() == ONLY_IN_TYPE }
                .map { it.clazz().className }
                .toSet()
        }

        val modids: Map<String, String> = scanData.annotations
            .asSequence()
            .filter { it.annotationType() == MOD_TYPE }
            .associate { it.clazz().className to (it.annotationData()["value"] as String) }

        val defaultSides = listOf(EnumData(null, "CLIENT"), EnumData(null, "DEDICATED_SERVER"))
        val defaultBus = EnumData(null, "FORGE")

        for (annotationData in targets) {
            if (!FMLEnvironment.production && onlyIns.contains(annotationData.clazz.className)) {
                throw RuntimeException("Found @OnlyIn on @EventBusSubscriber class ${annotationData.clazz().className} - this is not allowed as it causes crashes. Remove the OnlyIn and set value=Dist.CLIENT in the EventBusSubscriber annotation instead")
            }

            var modId = modids.getOrDefault(annotationData.clazz.className, mod.modId)
            modId = value(annotationData, "modId", modId)

            val sidesValue = value(annotationData, "value", defaultSides)
            val sides = sidesValue
                .map(EnumData::value)
                .map(Dist::valueOf)
                .toSet()
            val busName = value(annotationData, "bus", defaultBus).value()
            val busTarget = Mod.EventBusSubscriber.Bus.valueOf(busName)
            if (mod.modId == modId && FMLEnvironment.dist in sides) {
                try {
                    LOGGER.debug(LOADING, "Auto-subscribing {} to {}", annotationData.clazz().getClassName(), busTarget)
                    val clazz = Class.forName(annotationData.clazz.className, true, classLoader)

                    when (busTarget) {
                        Mod.EventBusSubscriber.Bus.MOD -> {
                            EventBusSubscriberLogic.register(KotlinModLoadingContext.get().getKBusGroup(), clazz)
                        }
                        Mod.EventBusSubscriber.Bus.FORGE -> {
                            EventBusSubscriberLogic.register(BusGroup.DEFAULT, clazz)
                        }
                        Mod.EventBusSubscriber.Bus.BOTH -> {
                            EventBusSubscriberLogic.register(KotlinModLoadingContext.get().getKBusGroup(), clazz)
                            EventBusSubscriberLogic.register(BusGroup.DEFAULT, clazz)
                        }
                    }
                } catch (e: ClassNotFoundException){
                    LOGGER.fatal(LOADING, "Failed to load mod class {} for @EventBusSubscriber annotation", annotationData.clazz(), e)
                    throw RuntimeException(e)
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <R> value(data: ModFileScanData.AnnotationData, key: String, default: R): R {
        return data.annotationData.getOrDefault(key, default) as R
    }

    private object EventBusSubscriberLogic {
        private val STRICT_RUNTIME_CHECKS = java.lang.Boolean.getBoolean("eventbus.api.strictRuntimeChecks")
        private val STRICT_REGISTRATION_CHECKS =
            STRICT_RUNTIME_CHECKS || java.lang.Boolean.getBoolean("eventbus.api.strictRegistrationChecks")

        private val RETURNS_CONSUMER = MethodType.methodType(Consumer::class.java)
        private val RETURNS_PREDICATE = MethodType.methodType(Predicate::class.java)
        private val RETURNS_MONITOR = MethodType.methodType(ObjBooleanBiConsumer::class.java)

        private val CONSUMER_FI_TYPE = MethodType.methodType(Void.TYPE, Any::class.java)
        private val PREDICATE_FI_TYPE = CONSUMER_FI_TYPE.changeReturnType(Boolean::class.javaPrimitiveType)
        private val MONITOR_FI_TYPE = MethodType.methodType(Void.TYPE, Any::class.java, Boolean::class.javaPrimitiveType)

        @JvmStatic
        fun register(busGroup: BusGroup?, listenerClass: Class<*>) {
            if (STRICT_REGISTRATION_CHECKS) registerStrict(busGroup, listenerClass)
            else registerLenient(busGroup, listenerClass)
        }

        @JvmStatic
        fun registerLenient(busGroup: BusGroup?, listenerClass: Class<*>) {
            val declaredMethods = listenerClass.declaredMethods
            if (declaredMethods.isEmpty()) throw IllegalArgumentException("No declared methods found in $listenerClass")

            var firstValidEvent: Class<*>? = null
            var listeners = 0

            for (method in declaredMethods) {
                if (!Modifier.isStatic(method.modifiers) || method.isSynthetic) continue
                if (method.parameterCount !in 1..2) continue
                if (method.returnType != Void.TYPE && method.returnType != Boolean::class.javaPrimitiveType) continue
                if (!method.isAnnotationPresent(SubscribeEvent::class.java)) continue

                val paramType = method.parameterTypes[0]
                if (!Event::class.java.isAssignableFrom(paramType))
                    throw IllegalArgumentException("First parameter must be an Event")

                val eventType = paramType as Class<out Event>
                val annotation = method.getAnnotation(SubscribeEvent::class.java)

                registerListenerGeneric(busGroup, method.parameterCount, method.returnType, eventType, annotation, method)
                if (firstValidEvent == null) firstValidEvent = eventType
                listeners++
            }

            if (listeners == 0) throw IllegalArgumentException("No valid listeners found in $listenerClass")
        }

        @JvmStatic
        fun registerStrict(busGroup: BusGroup?, listenerClass: Class<*>) {
            val declaredMethods = listenerClass.declaredMethods.filterNot { it.isSynthetic }
            if (declaredMethods.isEmpty()) {
                var msg = "No declared methods found in ${listenerClass.name}"
                listenerClass.superclass?.let {
                    if (it != Record::class.java && it != Enum::class.java) {
                        msg += ". Listener inheritance isn't supported. Use @Override + @SubscribeEvent on subclass."
                    }
                }
                throw fail(listenerClass, msg)
            }

            var firstValidEvent: Class<out Event>? = null
            var listeners = 0

            for (method in declaredMethods) {
                val hasAnnotation = method.isAnnotationPresent(SubscribeEvent::class.java)
                val paramCount = method.parameterCount
                val paramTypes = method.parameterTypes

                if (hasAnnotation && (paramCount == 0 || paramCount > 2))
                    throw fail(method, "Invalid parameter count: $paramCount")

                if (paramCount == 0) continue
                if (!hasAnnotation && Event::class.java.isAssignableFrom(paramTypes[0]))
                    throw fail(method, "Missing @SubscribeEvent annotation")

                if (hasAnnotation) {
                    val annotation = method.getAnnotation(SubscribeEvent::class.java)
                    val isMonitor = annotation.priority == Priority.MONITOR
                    val returnType = method.returnType

                    if (!Modifier.isStatic(method.modifiers)) throw fail(method, "Listener must be static")
                    if (!Event::class.java.isAssignableFrom(paramTypes[0])) throw fail(method, "First param must be Event")

                    val eventType = paramTypes[0] as Class<out Event>
                    val cancellable = Cancellable::class.java.isAssignableFrom(eventType)

                    if (returnType != Void.TYPE && returnType != Boolean::class.javaPrimitiveType)
                        throw fail(method, "Invalid return type: ${returnType.name}")

                    if (paramCount == 2) {
                        if (!cancellable) throw fail(method, "Second param only valid for cancellable events")
                        if (paramTypes[1] != Boolean::class.javaPrimitiveType) throw fail(method, "Second param must be boolean")
                        if (!isMonitor) throw fail(method, "Second param only valid for MONITOR priority")
                    }

                    if (!cancellable) {
                        if (annotation.alwaysCancelling)
                            throw fail(method, "alwaysCancelling only valid on cancellable events")
                        if (returnType == Boolean::class.javaPrimitiveType)
                            throw fail(method, "boolean return type only valid on cancellable events")
                    }

                    registerListenerGeneric(busGroup, paramCount, returnType, eventType, annotation, method)
                    if (firstValidEvent == null) firstValidEvent = eventType
                    listeners++
                }
            }

            if (listeners == 0) throw fail(listenerClass, "No valid listeners found")
        }

        @Suppress("UNCHECKED_CAST")
        fun <T> castToCancellableEvent(eventType: Class<*>): Class<T>
                where T : Event, T : Cancellable {
            return eventType as Class<T>
        }

        @Suppress("UNCHECKED_CAST")
        private fun <T : Event> registerListenerGeneric(
            busGroup: BusGroup?,
            paramCount: Int,
            returnType: Class<*>,
            eventType: Class<T>,
            annotation: SubscribeEvent,
            method: Method
        ) {
            val bus = busGroup ?: if (IModBusEvent::class.java.isAssignableFrom(eventType))
                KotlinModLoadingContext.get().getKBusGroup()
            else BusGroup.DEFAULT

            val listener: EventListener = when (paramCount) {
                1 -> {
                    val priority = annotation.priority
                    if (returnType == Void.TYPE) {
                        if (Cancellable::class.java.isAssignableFrom(eventType)) {
                            val busC = CancellableEventBus.create(
                                bus,
                                castToCancellableEvent(eventType)
                            )
                            if (annotation.alwaysCancelling)
                                busC.addListener(priority, true, createConsumer(method))
                            else
                                busC.addListener(priority, createConsumer(method))
                        } else {
                            EventBus.create(bus, eventType).addListener(priority, createConsumer(method))
                        }
                    } else {
                        if (annotation.alwaysCancelling)
                            throw fail(method, "Listeners returning boolean can't be alwaysCancelling")
                        if (!Cancellable::class.java.isAssignableFrom(eventType))
                            throw fail(method, "boolean return type only valid for cancellable events")

                        CancellableEventBus.create(bus,
                            castToCancellableEvent(eventType)
                        )
                            .addListener(priority, createPredicate(method))
                    }
                }

                else -> {
                    if (returnType != Void.TYPE) throw fail(method, "Cancellation-monitoring listeners must return void")
                    if (annotation.alwaysCancelling) throw fail(method, "MONITOR listeners cannot cancel")
                    CancellableEventBus.create(
                        bus,
                        castToCancellableEvent(eventType)
                    )
                        .addListener(createMonitor(method))
                }
            }

            // Optionally store listener if needed
        }

        private fun fail(clazz: Class<*>, reason: String): IllegalArgumentException =
            IllegalArgumentException("Failed to register ${clazz.name}: $reason")

        private fun fail(method: Method, reason: String): IllegalArgumentException =
            IllegalArgumentException("Failed to register ${method.declaringClass.name}.${method.name}: $reason")

        @Suppress("UNCHECKED_CAST")
        private fun <T : Event> createConsumer(method: Method): Consumer<T> =
            makeFactory(method, RETURNS_CONSUMER, CONSUMER_FI_TYPE, "accept").invokeExact() as Consumer<T>

        @Suppress("UNCHECKED_CAST")
        private fun <T : Event> createPredicate(method: Method): Predicate<T> =
            makeFactory(method, RETURNS_PREDICATE, PREDICATE_FI_TYPE, "test").invokeExact() as Predicate<T>

        @Suppress("UNCHECKED_CAST")
        private fun <T : Event> createMonitor(method: Method): ObjBooleanBiConsumer<T> =
            makeFactory(method, RETURNS_MONITOR, MONITOR_FI_TYPE, "accept").invokeExact() as ObjBooleanBiConsumer<T>

        private fun makeFactory(
            method: Method,
            factoryReturnType: MethodType,
            fiMethodType: MethodType,
            fiMethodName: String
        ): MethodHandle {
            val lookup = run {
                val field = MethodHandles.Lookup::class.java.getDeclaredField("IMPL_LOOKUP")
                UnsafeHacks.setAccessible(field)
                field.get(null) as MethodHandles.Lookup
            }.`in`(method.declaringClass)

            val target = lookup.unreflect(method)
            return LambdaMetafactory.metafactory(
                lookup, fiMethodName, factoryReturnType, fiMethodType, target, target.type()
            ).target
        }
    }
}

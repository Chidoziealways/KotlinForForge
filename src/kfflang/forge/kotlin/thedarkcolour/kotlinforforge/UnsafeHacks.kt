@file:Suppress("UNCHECKED_CAST", "MemberVisibilityCanBePrivate", "unused")

package thedarkcolour.kotlinforforge

import sun.misc.Unsafe
import java.lang.reflect.AccessibleObject
import java.lang.reflect.Field
import java.util.function.Consumer

public object UnsafeHacks {
    private val UNSAFE: Unsafe = getUnsafe()
    private val module: UnsafeFieldAccess<Class<*>, Any>? = findField(Class::class.java, "module")
    private val SETACCESSIBLE: Consumer<AccessibleObject> = getSetAccessible()

    public fun <T> newInstance(clazz: Class<T>): T {
        return try {
            UNSAFE.allocateInstance(clazz) as T
        } catch (e: InstantiationException) {
            sneak<InstantiationException, Nothing>(e)
        }
    }

    public fun <T> getField(field: Field, instance: Any): T {
        return UNSAFE.getObject(instance, UNSAFE.objectFieldOffset(field)) as T
    }

    public fun setField(field: Field, instance: Any, value: Any) {
        UNSAFE.putObject(instance, UNSAFE.objectFieldOffset(field), value)
    }

    fun getIntField(field: Field, instance: Any): Int {
        return UNSAFE.getInt(instance, UNSAFE.objectFieldOffset(field))
    }

    fun setIntField(field: Field, instance: Any, value: Int) {
        UNSAFE.putInt(instance, UNSAFE.objectFieldOffset(field), value)
    }

    public fun <O, T> findField(clazz: Class<O>, name: String): UnsafeFieldAccess<O, T>? {
        return clazz.declaredFields
            .firstOrNull { it.name == name }
            ?.let { UnsafeFieldAccess(UNSAFE.objectFieldOffset(it)) }
    }

    public fun <O> findIntField(clazz: Class<O>, name: String): UnsafeFieldAccess.Int<O>? {
        return clazz.declaredFields
            .firstOrNull { it.name == name }
            ?.let { UnsafeFieldAccess.Int(UNSAFE.objectFieldOffset(it)) }
    }

    public fun <O> findBooleanField(clazz: Class<O>, name: String): UnsafeFieldAccess.Bool<O>? {
        return clazz.declaredFields
            .firstOrNull { it.name == name }
            ?.let { UnsafeFieldAccess.Bool(UNSAFE.objectFieldOffset(it)) }
    }

    public fun setAccessible(target: AccessibleObject) {
        SETACCESSIBLE.accept(target)
    }

    public fun theUnsafe(): Unsafe = UNSAFE

    private fun getUnsafe(): Unsafe {
        return try {
            val field = Unsafe::class.java.getDeclaredField("theUnsafe")
            field.isAccessible = true
            field.get(null) as Unsafe
        } catch (e: Exception) {
            sneak<Exception, Nothing>(e)
        }
    }

    private fun getSetAccessible(): Consumer<AccessibleObject> {
        return try {
            val method = try {
                AccessibleObject::class.java.getDeclaredMethod("setAccessible0", Boolean::class.javaPrimitiveType)
            } catch (e: NoSuchMethodException) {
                AccessibleObject::class.java.getDeclaredMethod("setAccessible0", AccessibleObject::class.java, Boolean::class.javaPrimitiveType)
            }

            setAccessibleFallback(method)

            Consumer { acc ->
                try {
                    method.invoke(acc, true)
                } catch (e: Exception) {
                    sneak<RuntimeException, Nothing>(e)
                }
            }
        } catch (e: Exception) {
            sneak<RuntimeException, Nothing>(e)
        }
    }

    private fun setAccessibleFallback(obj: AccessibleObject) {
        if (module != null) {
            val old = module.get(UnsafeHacks::class.java)
            val base = module.get(Any::class.java)
            module.set(UnsafeHacks::class.java, base)

            obj.isAccessible = true

            module.set(UnsafeHacks::class.java, old)
        } else {
            obj.isAccessible = true
        }
    }

    public inline fun <reified E : Throwable, R> sneak(e: Throwable): R {
        throw e as E
    }
}

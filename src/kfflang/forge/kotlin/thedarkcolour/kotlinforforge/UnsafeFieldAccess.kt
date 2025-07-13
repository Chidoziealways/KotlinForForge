@file:Suppress("UNCHECKED_CAST", "MemberVisibilityCanBePrivate", "unused")

package thedarkcolour.kotlinforforge

public open class UnsafeFieldAccess<Owner, Type>(protected val index: Long) {
    public open fun <I : Owner> get(instance: I): Type {
        return UnsafeHacks.theUnsafe().getObject(instance, index) as Type
    }

    public open fun <I : Owner> set(instance: I, value: Type) {
        UnsafeHacks.theUnsafe().putObject(instance, index, value)
    }

    public class Int<Owner>(index: Long) : UnsafeFieldAccess<Owner, kotlin.Int>(index) {
        override fun <I : Owner> get(instance: I): kotlin.Int = getInt(instance)
        override fun <I : Owner> set(instance: I, value: kotlin.Int): Unit = setInt(instance, value)

        public fun <I : Owner> getInt(instance: I): kotlin.Int {
            return UnsafeHacks.theUnsafe().getInt(instance, index)
        }

        public fun <I : Owner> setInt(instance: I, value: kotlin.Int) {
            UnsafeHacks.theUnsafe().putInt(instance, index, value)
        }
    }

    public class Bool<Owner>(index: Long) : UnsafeFieldAccess<Owner, Boolean>(index) {
        override fun <I : Owner> get(instance: I): Boolean = getBoolean(instance)
        override fun <I : Owner> set(instance: I, value: Boolean): Unit = setBoolean(instance, value)

        public fun <I : Owner> getBoolean(instance: I): Boolean {
            return UnsafeHacks.theUnsafe().getBoolean(instance, index)
        }

        public fun <I : Owner> setBoolean(instance: I, value: Boolean) {
            UnsafeHacks.theUnsafe().putBoolean(instance, index, value)
        }
    }
}

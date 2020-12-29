/**
 * Copyright 2010 - 2020 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package jetbrains.exodus.bindings;

import jetbrains.exodus.ExodusException;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public class ComparableValueType {

    public static final int STRING_VALUE_TYPE = 2;
    public static final int DOUBLE_VALUE_TYPE = 3;
    public static final int FLOAT_VALUE_TYPE = 7;
    public static final int COMPARABLE_SET_VALUE_TYPE = 8;

    public static final ComparableValueType[] PREDEFINED_COMPARABLE_VALUE_TYPES;

    private static final Class[] PREDEFINED_CLASSES = {
            Integer.class,
            Long.class,
            String.class,
            Double.class,
            Byte.class,
            Boolean.class,
            Short.class,
            Float.class,
            ComparableSet.class
    };
    private static final ComparableBinding[] PREDEFINED_BINDINGS = {
            IntegerBinding.BINDING,
            LongBinding.BINDING,
            StringBinding.BINDING,
            SignedDoubleBinding.BINDING,
            ByteBinding.BINDING,
            BooleanBinding.BINDING,
            ShortBinding.BINDING,
            SignedFloatBinding.BINDING,
            ComparableSetBinding.BINDING
    };

    static {
        PREDEFINED_COMPARABLE_VALUE_TYPES = new ComparableValueType[PREDEFINED_CLASSES.length];
        for (int i = 0; i < PREDEFINED_COMPARABLE_VALUE_TYPES.length; i++) {
            //noinspection unchecked
            PREDEFINED_COMPARABLE_VALUE_TYPES[i] = new ComparableValueType(i, PREDEFINED_BINDINGS[i], PREDEFINED_CLASSES[i]);
        }
    }

    private final int typeId;
    private final ComparableBinding binding;
    private final Class<? extends Comparable> clazz;
    public static final List<ComparableValueType> registeredTypes = new ArrayList<>();


    public ComparableValueType(final int typeId, @NotNull final ComparableBinding binding, @NotNull final Class<? extends Comparable> clazz) {
        this.typeId = typeId;
        this.binding = binding;
        this.clazz = clazz;
    }

    public int getTypeId() {
        return typeId;
    }

    public ComparableBinding getBinding() {
        return binding;
    }

    public Class<? extends Comparable> getClazz() {
        return clazz;
    }

    public static ComparableBinding getPredefinedBinding(final int typeId) {
        for(ComparableValueType type : registeredTypes) {
            if(type.getTypeId() == typeId) {
                return type.getBinding();
            }
        }
        ComparableBinding binding = PREDEFINED_BINDINGS[typeId];
        return binding;
    }

    public static ComparableValueType getPredefinedType(@NotNull final Class<? extends Comparable> clazz) {
        for (int i = 0; i < PREDEFINED_CLASSES.length; i++) {
            if (PREDEFINED_CLASSES[i].equals(clazz)) {
                return PREDEFINED_COMPARABLE_VALUE_TYPES[i];
            }
        }
        for(ComparableValueType type : registeredTypes) {
            if(type.clazz.isAssignableFrom(clazz)) {
                return type;
            }
        }
        throw new ExodusException("Unsupported Comparable value type: " + clazz);
    }

    public static void registerCustomType(int typeId,
                                          @NotNull final Class<? extends Comparable> clazz,
                                          @NotNull final ComparableBinding binding) {
        registeredTypes.add(new ComparableValueType(typeId, binding, clazz));
    }
}

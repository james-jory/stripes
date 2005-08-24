/* Copyright (C) 2005 Tim Fennell
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by the
 * Free Software Foundation; either version 2.1 of the License, or (at your
 * option) any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the license with this software. If not,
 * it can be found online at http://www.fsf.org/licensing/licenses/lgpl.html
 */
package net.sourceforge.stripes.util;

import ognl.*;

import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.lang.reflect.ParameterizedType;
import java.beans.IntrospectionException;

/**
 * Utility class used to configure and use Ognl in a consistent manner across the pieces of
 * Stripes that use it.  Ensures that the Stripes specific OgnlCustomNullHandler is registered with
 * the OgnlRuntime before it is used, and that the appropriate OgnlContexts are used.  Also provides
 * facilities for caching the Ognl expression objects that are created from parsing property names -
 * this provides a significant performance boost.
 *
 * @author Tim Fennell
 */
public class OgnlUtil {
    /** Store for pre-parsed expressions. */
    private static Map<String,Object> expressions = new HashMap<String,Object>();

    /** Static initializer block that inserts a custom NullHandler into the OgnlRuntime. */
    static {
        OgnlRuntime.setNullHandler(Object.class, new OgnlCustomNullHandler());
        OgnlRuntime.setPropertyAccessor(List.class, new OgnlSafeListPropertyAccessor());
    }

    /** Private default constructor to prevent anyone from instantiating the class. */
    private OgnlUtil() {
        // Do Nothing
    }

    /**
     * Fetches the value of a named property (nested, indexed etc.) using Ognl.
     *
     * @param property an expression representing the property desired
     * @param root the object from which to fetch the property
     * @return Object the value of the property, or null if the property is null
     * @throws OgnlException thrown when a problem occurs such as one or more properties not
     *         existing or being inaccessible
     */
    public static Object getValue(String property, Object root) throws OgnlException {
        return Ognl.getValue(getExpression(property), createContext(), root);
    }

    /**
     * Fetches the value of a named property (nested, indexed etc.) using Ognl. Can optionally
     * instantiate the final property if it is null, and return a default instance of the
     * type.
     *
     * @param property an expression representing the property desired
     * @param root the object from which to fetch the property
     * @param instantiateLeaf if true, an attmept will be made to instantiate the property if it
     *        is null, and return a default value.
     * @return Object the value of the property, or null if the property is null
     * @throws OgnlException thrown when a problem occurs such as one or more properties not
     *         existing or being inaccessible
     */
    public static Object getValue(String property, Object root, boolean instantiateLeaf) throws OgnlException {
        OgnlContext context = createContext();
        if (instantiateLeaf) {
            context.put(OgnlCustomNullHandler.INSTANTIATE_LEAF_NODES,
                        OgnlCustomNullHandler.INSTANTIATE_LEAF_NODES);
        }

        return Ognl.getValue(getExpression(property), context, root);
    }

    /**
     * Sets the value of a named property (nested, indexed etc.) using Ognl. If any of the values
     * along the way are not set then the values are created and set into the object graph.
     *
     * @param property an expression representing the property to set
     * @param root the object on which to set the property
     * @param value the value of the property to be set
     * @throws OgnlException thrown when a problem occurs such as one or more properties not
     *         existing or being inaccessible
     */
    public static void setValue(String property, Object root, Object value) throws OgnlException {
        Ognl.setValue(getExpression(property), createContext(), root, value);
    }

    /**
     * Retrieves the class type of the specified property without instantiating the property
     * itself. All earlier properties in a chain will, however, be instantiated.
     *
     * @param property an expression for the property to be inspected
     * @param root the object on which the property lives
     * @return the Class type of the property
     * @throws OgnlException thrown when a problem occurs such as one or more properties not
     *         being inaccessible
     * @throws NoSuchPropertyException thrown when the property does not exist
     */
    public static Class getPropertyClass(String property, Object root) throws OgnlException,
                                                                              IntrospectionException {
        Class propertyClass = null;

        int propertyIndex = propertySplit(property);

        if (propertyIndex > 0) {
            // foo.bar.baz.splat => parent: "foo.bar.baz" and child: "splat"
            String parentProperty = property.substring(0,propertyIndex);
            String childProperty = property.substring(propertyIndex+1);

            // We need to handle the case where the last chunk of the expression references
            // a List or a Map
            int listIndexPosition = childProperty.indexOf("[");

            if (listIndexPosition > 0) {
                Object newRoot = getValue(parentProperty, root, true);
                Method method = OgnlRuntime.getGetMethod(createContext(),
                                                         newRoot.getClass(),
                                                         childProperty.substring(0, listIndexPosition));
                Type returnType = method.getGenericReturnType();
                if (returnType instanceof ParameterizedType) {
                    ParameterizedType ptype = (ParameterizedType) returnType;
                    Type actualType = null;

                    // Get the right type parameter for List<X> and Map<?,X>
                    if (List.class.isAssignableFrom((Class) ptype.getRawType())) {
                        actualType = ptype.getActualTypeArguments()[0];
                    }
                    else if (Map.class.isAssignableFrom((Class) ptype.getRawType())) {
                        actualType = ptype.getActualTypeArguments()[1];
                    }

                    if (actualType instanceof Class) {
                        propertyClass = (Class) actualType;
                    }
                }

                // If we can't figure it out, let's try String!
                if (propertyClass == null) {
                    propertyClass = String.class;
                }
            }
            else {
                Object newRoot = getValue(parentProperty,root, true);
                Method method =
                        OgnlRuntime.getGetMethod(createContext(), newRoot.getClass(), childProperty);
                if (method == null) {
                    throw new NoSuchPropertyException(root,property);
                }
                propertyClass = method.getReturnType();
            }
        }
        else {
            Method method =
                    OgnlRuntime.getGetMethod(createContext(), root.getClass(), property);
            if (method == null) {
                throw new NoSuchPropertyException(root,property);
            }
            propertyClass = method.getReturnType();
        }

        return propertyClass;
    }

    /**
     * Finds the split point for the last property of an OGNL expression. If the last
     * property includes a map reference, the map reference is part of the last property
     * expression. This method also accounts for literals in single-ticks inside of that
     * last map expression.
     * @param property the property expression to evaluate
     * @return the index of the property splitter for the last property of the expression
     */
    private static int propertySplit(String property) {
        int endTick = property.lastIndexOf("'");
        int childSplit;
        if (endTick > 0) {
            // We have ticks, assume there are two and they're a map literal (e.g. foo.bar['baz.splat'])
            String first = property.substring(0,endTick);
            int startTick = first.lastIndexOf("'");
            assert startTick > 0 : "Ticks in expression don't match";

            childSplit = property.lastIndexOf('.');
            if (childSplit > startTick && childSplit < endTick) {
                String beforeTicks = property.substring(0, startTick);
                childSplit = beforeTicks.lastIndexOf('.');
            }
        } else {
            childSplit = property.lastIndexOf('.');
        }
        return childSplit;
    }

    /**
     * Supplies the Ognl expression object for a given property name string.  The property name
     * can be any combination of the types that Ognl can handle (simple, indexed, nested etc.).
     * First checks to see if a cached expression object exists, and if so supplied that. If no
     * cached expression exists, one is manufactured, placed into the cache and then returned.
     *
     * @param property the property name string being used to access a property
     * @return an Ognl expression object representing the property name string
     * @throws OgnlException if the property name is not a well formed Ognl property name string
     */
    private static synchronized Object getExpression(String property) throws OgnlException {
        Object ognlExpression = OgnlUtil.expressions.get(property);
        if (ognlExpression == null) {
            ognlExpression = Ognl.parseExpression(property);
            OgnlUtil.expressions.put(property, ognlExpression);
        }

        return ognlExpression;
    }

    /**
     * Manufactures an OgnlContext configured as needed for Stripes to operate correctly.
     *
     * @return a pre-configured OgnlContext
     */
    private static OgnlContext createContext() {
        OgnlContext context = new OgnlContext();
        context.setTraceEvaluations(true);
        return context;
    }
}

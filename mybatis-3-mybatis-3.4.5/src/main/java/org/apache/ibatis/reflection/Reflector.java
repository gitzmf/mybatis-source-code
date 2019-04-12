/**
 *    Copyright ${license.git.copyrightYears} the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.reflection;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.ReflectPermission;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.ibatis.reflection.invoker.GetFieldInvoker;
import org.apache.ibatis.reflection.invoker.Invoker;
import org.apache.ibatis.reflection.invoker.MethodInvoker;
import org.apache.ibatis.reflection.invoker.SetFieldInvoker;
import org.apache.ibatis.reflection.property.PropertyNamer;

/**
 * This class represents a cached set of class definition information that
 * allows for easy mapping between property names and getter/setter methods.
 *
 * @author Clinton Begin Mybatis反射模块的基础
 */
public class Reflector {

	// 对应的Class类型
	private final Class<?> type;
	// 可读属性的名称集合，可读属性就是存在相应的getter方法的属性，初始值为空数组
	private final String[] readablePropertyNames;
	// 可读属性的名称集合，可写属性就是存在相应的getter方法的属性，初始值为空数组
	private final String[] writeablePropertyNames;
	// set方法集合 key是属性value是Invoker对象
	private final Map<String, Invoker> setMethods = new HashMap<String, Invoker>();
	// get方法集合
	private final Map<String, Invoker> getMethods = new HashMap<String, Invoker>();
	// 属性相应的setter方法的参数类型
	private final Map<String, Class<?>> setTypes = new HashMap<String, Class<?>>();
	// 属性相应的getter方法的返回值类型
	private final Map<String, Class<?>> getTypes = new HashMap<String, Class<?>>();
	// 记录默认的构造方法
	private Constructor<?> defaultConstructor;
	// 记录所有属性的名称集合
	private Map<String, String> caseInsensitivePropertyMap = new HashMap<String, String>();

	// 解析指定的Class对象，并填充上述集合
	public Reflector(Class<?> clazz) {
		type = clazz;
		// 查找默认的构造方法
		addDefaultConstructor(clazz);
		// 处理getter方法，填充getMethods集合和getTypes集合
		addGetMethods(clazz);
		// 处理setter方法，填充setMethods集合和setTypes集合
		addSetMethods(clazz);
		// 处理没有getter/setter方法的字段
		addFields(clazz);

		// 根据getMethods/setMethods集合，初始化可读/可写属性名称的集合
		readablePropertyNames = getMethods.keySet().toArray(new String[getMethods.keySet().size()]);
		writeablePropertyNames = setMethods.keySet().toArray(new String[setMethods.keySet().size()]);
		// 初始化caseInsensitivePropertyMap集合，其中记录了所有大写格式的属性名称
		for (String propName : readablePropertyNames) {
			caseInsensitivePropertyMap.put(propName.toUpperCase(Locale.ENGLISH), propName);
		}
		for (String propName : writeablePropertyNames) {
			caseInsensitivePropertyMap.put(propName.toUpperCase(Locale.ENGLISH), propName);
		}
	}

	private void addDefaultConstructor(Class<?> clazz) {
		Constructor<?>[] consts = clazz.getDeclaredConstructors();
		for (Constructor<?> constructor : consts) {
			if (constructor.getParameterTypes().length == 0) {
				if (canAccessPrivateMethods()) {
					try {
						constructor.setAccessible(true);
					} catch (Exception e) {
						// Ignored. This is only a final precaution, nothing we
						// can do.
					}
				}
				if (constructor.isAccessible()) {
					this.defaultConstructor = constructor;
				}
			}
		}
	}

	private void addGetMethods(Class<?> cls) {
		//因为一个属性可能对应多个getter方法所以要用List集合
		Map<String, List<Method>> conflictingGetters = new HashMap<String, List<Method>>();
		//获取指定类以及父类和接口中定义的方法
		Method[] methods = getClassMethods(cls);
		//按照JavaBean规范查找getter方法并记录到conflictingGetters集合中
		for (Method method : methods) {
			// 如果有参数，直接跳过
			if (method.getParameterTypes().length > 0) {
				continue;
			}
			String name = method.getName();
			//方法名以get开头或is开头
			if ((name.startsWith("get") && name.length() > 3) || (name.startsWith("is") && name.length() > 2)) {
				// 获取对应的属性名称
				name = PropertyNamer.methodToProperty(name);
				// 查找该类定义的getter方法，记录到conflictingGetters集合
				addMethodConflict(conflictingGetters, name, method);
			}
		}
		//对子类覆写父类但返回值类型不同的情况下，会签名不同，被认为是两种不同的方法，针对这一情况进行处理
		resolveGetterConflicts(conflictingGetters);
	}

	private void resolveGetterConflicts(Map<String, List<Method>> conflictingGetters) {
		// 遍历集合
		for (Entry<String, List<Method>> entry : conflictingGetters.entrySet()) {
			//当前最适合的getter方法
			Method winner = null;
			// 属性名
			String propName = entry.getKey();
			for (Method candidate : entry.getValue()) {
				if (winner == null) {
					winner = candidate;
					continue;
				}
				// 最适合方法的返回值类型
				Class<?> winnerType = winner.getReturnType();
				// 当前方法的返回值类型
				Class<?> candidateType = candidate.getReturnType();
				// 如果返回值类型一样
				if (candidateType.equals(winnerType)) {
					if (!boolean.class.equals(candidateType)) {
						//直接刨出异常
						throw new ReflectionException(
								"Illegal overloaded getter method with ambiguous type for property " + propName
										+ " in class " + winner.getDeclaringClass()
										+ ". This breaks the JavaBeans specification and can cause unpredictable results.");
					} else if (candidate.getName().startsWith("is")) {
						winner = candidate;
					}
				} else if (candidateType.isAssignableFrom(winnerType)) {
					// 当前最适合的方法返回值类型是当前方法返回值的子类，什么都不做
					// OK getter type is descendant
				} else if (winnerType.isAssignableFrom(candidateType)) {
					// 当前方法返回值类型是当前最适合方法返回值的子类，什么都不做
					winner = candidate;
				} else {
					throw new ReflectionException("Illegal overloaded getter method with ambiguous type for property "
							+ propName + " in class " + winner.getDeclaringClass()
							+ ". This breaks the JavaBeans specification and can cause unpredictable results.");
				}
			}
			// 把最适合的方法和属性方法添加到getMethods集合
			addGetMethod(propName, winner);
		}
	}

	private void addGetMethod(String name, Method method) {
		// 检查属性名是否合法
		if (isValidPropertyName(name)) {
			//将属性名和对应的MethodInvoker对象添加到getMethods集合中
			getMethods.put(name, new MethodInvoker(method));
			//获取返回值的Type
			Type returnType = TypeParameterResolver.resolveReturnType(method, type);
			//将属性名称和getter方法的返回值类型添加到getTypes集合中保存
			getTypes.put(name, typeToClass(returnType));
		}
	}

	private void addSetMethods(Class<?> cls) {
		Map<String, List<Method>> conflictingSetters = new HashMap<String, List<Method>>();
		Method[] methods = getClassMethods(cls);
		for (Method method : methods) {
			String name = method.getName();
			if (name.startsWith("set") && name.length() > 3) {
				if (method.getParameterTypes().length == 1) {
					name = PropertyNamer.methodToProperty(name);
					addMethodConflict(conflictingSetters, name, method);
				}
			}
		}
		resolveSetterConflicts(conflictingSetters);
	}

	private void addMethodConflict(Map<String, List<Method>> conflictingMethods, String name, Method method) {
		List<Method> list = conflictingMethods.get(name);
		if (list == null) {
			list = new ArrayList<Method>();
			conflictingMethods.put(name, list);
		}
		list.add(method);
	}

	private void resolveSetterConflicts(Map<String, List<Method>> conflictingSetters) {
		for (String propName : conflictingSetters.keySet()) {
			List<Method> setters = conflictingSetters.get(propName);
			Class<?> getterType = getTypes.get(propName);
			Method match = null;
			ReflectionException exception = null;
			for (Method setter : setters) {
				Class<?> paramType = setter.getParameterTypes()[0];
				if (paramType.equals(getterType)) {
					// should be the best match
					match = setter;
					break;
				}
				if (exception == null) {
					try {
						match = pickBetterSetter(match, setter, propName);
					} catch (ReflectionException e) {
						// there could still be the 'best match'
						match = null;
						exception = e;
					}
				}
			}
			if (match == null) {
				throw exception;
			} else {
				addSetMethod(propName, match);
			}
		}
	}

	private Method pickBetterSetter(Method setter1, Method setter2, String property) {
		if (setter1 == null) {
			return setter2;
		}
		Class<?> paramType1 = setter1.getParameterTypes()[0];
		Class<?> paramType2 = setter2.getParameterTypes()[0];
		if (paramType1.isAssignableFrom(paramType2)) {
			return setter2;
		} else if (paramType2.isAssignableFrom(paramType1)) {
			return setter1;
		}
		throw new ReflectionException(
				"Ambiguous setters defined for property '" + property + "' in class '" + setter2.getDeclaringClass()
						+ "' with types '" + paramType1.getName() + "' and '" + paramType2.getName() + "'.");
	}

	private void addSetMethod(String name, Method method) {
		if (isValidPropertyName(name)) {
			setMethods.put(name, new MethodInvoker(method));
			Type[] paramTypes = TypeParameterResolver.resolveParamTypes(method, type);
			setTypes.put(name, typeToClass(paramTypes[0]));
		}
	}

	private Class<?> typeToClass(Type src) {
		Class<?> result = null;
		if (src instanceof Class) {
			result = (Class<?>) src;
		} else if (src instanceof ParameterizedType) {
			result = (Class<?>) ((ParameterizedType) src).getRawType();
		} else if (src instanceof GenericArrayType) {
			Type componentType = ((GenericArrayType) src).getGenericComponentType();
			if (componentType instanceof Class) {
				result = Array.newInstance((Class<?>) componentType, 0).getClass();
			} else {
				Class<?> componentClass = typeToClass(componentType);
				result = Array.newInstance((Class<?>) componentClass, 0).getClass();
			}
		}
		if (result == null) {
			result = Object.class;
		}
		return result;
	}

	private void addFields(Class<?> clazz) {
		//获取clazz中定义的全部字段
		Field[] fields = clazz.getDeclaredFields();
		for (Field field : fields) {
			if (canAccessPrivateMethods()) {
				try {
					field.setAccessible(true);
				} catch (Exception e) {
					// Ignored. This is only a final precaution, nothing we can
					// do.
				}
			}
			if (field.isAccessible()) {
				// 如果setMethods集合不包含字段，则添加到setMethods和setTypes集合中
				if (!setMethods.containsKey(field.getName())) {
					// issue #379 - removed the check for final because JDK 1.5
					// allows
					// modification of final fields through reflection
					// (JSR-133). (JGB)
					// pr #16 - final static can only be set by the classloader
					int modifiers = field.getModifiers();
					if (!(Modifier.isFinal(modifiers) && Modifier.isStatic(modifiers))) {
						// 添加到setMethods和setTypes集合中
						addSetField(field);
					}
				}
				// 如果getMethods集合不包含字段，则添加到getMethods和getTypes集合中
				if (!getMethods.containsKey(field.getName())) {
					//添加到getMethods和getTypes集合中
					addGetField(field);
				}
			}
		}
		if (clazz.getSuperclass() != null) {
			// 迭代处理父类中的字段
			addFields(clazz.getSuperclass());
		}
	}

	private void addSetField(Field field) {
		if (isValidPropertyName(field.getName())) {
			setMethods.put(field.getName(), new SetFieldInvoker(field));
			Type fieldType = TypeParameterResolver.resolveFieldType(field, type);
			setTypes.put(field.getName(), typeToClass(fieldType));
		}
	}

	private void addGetField(Field field) {
		if (isValidPropertyName(field.getName())) {
			getMethods.put(field.getName(), new GetFieldInvoker(field));
			Type fieldType = TypeParameterResolver.resolveFieldType(field, type);
			getTypes.put(field.getName(), typeToClass(fieldType));
		}
	}

	private boolean isValidPropertyName(String name) {
		return !(name.startsWith("$") || "serialVersionUID".equals(name) || "class".equals(name));
	}

	/*
	 * This method returns an array containing all methods declared in this
	 * class and any superclass. We use this method, instead of the simpler
	 * Class.getMethods(), because we want to look for private methods as well.
	 *
	 * @param cls The class
	 * 
	 * @return An array containing all methods in this class
	 */
	private Method[] getClassMethods(Class<?> cls) {
		// 用于记录指定类中全部方法的唯一签名和对应的Method对象
		Map<String, Method> uniqueMethods = new HashMap<String, Method>();
		Class<?> currentClass = cls;
		while (currentClass != null) {
			// 记录currentClass这个类中的定义的所有方法
			addUniqueMethods(uniqueMethods, currentClass.getDeclaredMethods());

			// we also need to look for interface methods -
			// because the class may be abstract
			// 记录接口中定义的方法
			Class<?>[] interfaces = currentClass.getInterfaces();
			for (Class<?> anInterface : interfaces) {
				addUniqueMethods(uniqueMethods, anInterface.getMethods());
			}
			// 获取父类，继续进行循环
			currentClass = currentClass.getSuperclass();
		}

		Collection<Method> methods = uniqueMethods.values();
		// 转换成Methods数组返回
		return methods.toArray(new Method[methods.size()]);
	}

	private void addUniqueMethods(Map<String, Method> uniqueMethods, Method[] methods) {
		for (Method currentMethod : methods) {
			if (!currentMethod.isBridge()) {
				// 得到方法的签名：返回值类型#方法名称:参数类型列表 全局唯一
				String signature = getSignature(currentMethod);
				// check to see if the method is already known
				// if it is known, then an extended class must have
				// overridden a method
				// 检查是否在子类中添加过该方法，如果添加过，则表示子类覆盖了该方法，无需在向集合中添加
				if (!uniqueMethods.containsKey(signature)) {
					if (canAccessPrivateMethods()) {
						try {
							currentMethod.setAccessible(true);
						} catch (Exception e) {
							// Ignored. This is only a final precaution, nothing
							// we can do.
						}
					}
					// 记录该签名和方法的对应关系
					uniqueMethods.put(signature, currentMethod);
				}
			}
		}
	}

	// 生成方法的唯一签名
	private String getSignature(Method method) {
		StringBuilder sb = new StringBuilder();
		Class<?> returnType = method.getReturnType();
		if (returnType != null) {
			sb.append(returnType.getName()).append('#');
		}
		sb.append(method.getName());
		Class<?>[] parameters = method.getParameterTypes();
		for (int i = 0; i < parameters.length; i++) {
			if (i == 0) {
				sb.append(':');
			} else {
				sb.append(',');
			}
			sb.append(parameters[i].getName());
		}
		return sb.toString();
	}

	private static boolean canAccessPrivateMethods() {
		try {
			SecurityManager securityManager = System.getSecurityManager();
			if (null != securityManager) {
				securityManager.checkPermission(new ReflectPermission("suppressAccessChecks"));
			}
		} catch (SecurityException e) {
			return false;
		}
		return true;
	}

	/*
	 * Gets the name of the class the instance provides information for
	 *
	 * @return The class name
	 */
	public Class<?> getType() {
		return type;
	}

	public Constructor<?> getDefaultConstructor() {
		if (defaultConstructor != null) {
			return defaultConstructor;
		} else {
			throw new ReflectionException("There is no default constructor for " + type);
		}
	}

	public boolean hasDefaultConstructor() {
		return defaultConstructor != null;
	}

	public Invoker getSetInvoker(String propertyName) {
		Invoker method = setMethods.get(propertyName);
		if (method == null) {
			throw new ReflectionException(
					"There is no setter for property named '" + propertyName + "' in '" + type + "'");
		}
		return method;
	}

	public Invoker getGetInvoker(String propertyName) {
		Invoker method = getMethods.get(propertyName);
		if (method == null) {
			throw new ReflectionException(
					"There is no getter for property named '" + propertyName + "' in '" + type + "'");
		}
		return method;
	}

	/*
	 * Gets the type for a property setter
	 *
	 * @param propertyName - the name of the property
	 * 
	 * @return The Class of the propery setter
	 */
	public Class<?> getSetterType(String propertyName) {
		Class<?> clazz = setTypes.get(propertyName);
		if (clazz == null) {
			throw new ReflectionException(
					"There is no setter for property named '" + propertyName + "' in '" + type + "'");
		}
		return clazz;
	}

	/*
	 * Gets the type for a property getter
	 *
	 * @param propertyName - the name of the property
	 * 
	 * @return The Class of the propery getter
	 */
	public Class<?> getGetterType(String propertyName) {
		Class<?> clazz = getTypes.get(propertyName);
		if (clazz == null) {
			throw new ReflectionException(
					"There is no getter for property named '" + propertyName + "' in '" + type + "'");
		}
		return clazz;
	}

	/*
	 * Gets an array of the readable properties for an object
	 *
	 * @return The array
	 */
	public String[] getGetablePropertyNames() {
		return readablePropertyNames;
	}

	/*
	 * Gets an array of the writeable properties for an object
	 *
	 * @return The array
	 */
	public String[] getSetablePropertyNames() {
		return writeablePropertyNames;
	}

	/*
	 * Check to see if a class has a writeable property by name
	 *
	 * @param propertyName - the name of the property to check
	 * 
	 * @return True if the object has a writeable property by the name
	 */
	public boolean hasSetter(String propertyName) {
		return setMethods.keySet().contains(propertyName);
	}

	/*
	 * Check to see if a class has a readable property by name
	 *
	 * @param propertyName - the name of the property to check
	 * 
	 * @return True if the object has a readable property by the name
	 */
	public boolean hasGetter(String propertyName) {
		return getMethods.keySet().contains(propertyName);
	}

	public String findPropertyName(String name) {
		return caseInsensitivePropertyMap.get(name.toUpperCase(Locale.ENGLISH));
	}
}

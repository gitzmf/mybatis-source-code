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
package org.apache.ibatis.type;

import java.io.InputStream;
import java.io.Reader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.ibatis.io.ResolverUtil;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.reflection.Jdk;

/**
 * @author Clinton Begin
 * @author Kazuki Shimizu 管理TypeHandler接口的众多实现类以及何时使用
 *         在Mybatis初始化过程中，会为所有已知的TypeHandler创建对象，并实现注册到TypeHandlerRegistry中，
 *         由TypeHandlerRegistry负责管理这些对象
 */
public final class TypeHandlerRegistry {
	//记录jdbcType类型与TypeHandler之间类型的对应关系,用于结果集取数据的时候，将数据从jdbc转为java类型
	private final Map<JdbcType, TypeHandler<?>> JDBC_TYPE_HANDLER_MAP = new EnumMap<JdbcType, TypeHandler<?>>(
			JdbcType.class);
	//记录java类型向指定TypeHandler之间类型的对应关系, 需要使用TypeHandler对象，因为可能一个java类型对应多种数据库类型，所以存在一对多的关系
	private final Map<Type, Map<JdbcType, TypeHandler<?>>> TYPE_HANDLER_MAP = new ConcurrentHashMap<Type, Map<JdbcType, TypeHandler<?>>>();
	private final TypeHandler<Object> UNKNOWN_TYPE_HANDLER = new UnknownTypeHandler(this);
	//记录了全部的typeHandler的类型以及该类型对应的TypeHandler对象
	private final Map<Class<?>, TypeHandler<?>> ALL_TYPE_HANDLERS_MAP = new HashMap<Class<?>, TypeHandler<?>>();
	
	//空集合对象的类型标识
	private static final Map<JdbcType, TypeHandler<?>> NULL_TYPE_HANDLER_MAP = new HashMap<JdbcType, TypeHandler<?>>();

	private Class<? extends TypeHandler> defaultEnumTypeHandler = EnumTypeHandler.class;
	
	//构造方法 为很多基础类型注册对应的TypeHandler对象
	public TypeHandlerRegistry() {
		register(Boolean.class, new BooleanTypeHandler());
		register(boolean.class, new BooleanTypeHandler());
		register(JdbcType.BOOLEAN, new BooleanTypeHandler());
		register(JdbcType.BIT, new BooleanTypeHandler());

		register(Byte.class, new ByteTypeHandler());
		register(byte.class, new ByteTypeHandler());
		register(JdbcType.TINYINT, new ByteTypeHandler());

		register(Short.class, new ShortTypeHandler());
		register(short.class, new ShortTypeHandler());
		register(JdbcType.SMALLINT, new ShortTypeHandler());

		register(Integer.class, new IntegerTypeHandler());
		register(int.class, new IntegerTypeHandler());
		register(JdbcType.INTEGER, new IntegerTypeHandler());

		register(Long.class, new LongTypeHandler());
		register(long.class, new LongTypeHandler());

		register(Float.class, new FloatTypeHandler());
		register(float.class, new FloatTypeHandler());
		register(JdbcType.FLOAT, new FloatTypeHandler());

		register(Double.class, new DoubleTypeHandler());
		register(double.class, new DoubleTypeHandler());
		register(JdbcType.DOUBLE, new DoubleTypeHandler());

		register(Reader.class, new ClobReaderTypeHandler());
		register(String.class, new StringTypeHandler());
		register(String.class, JdbcType.CHAR, new StringTypeHandler());
		register(String.class, JdbcType.CLOB, new ClobTypeHandler());
		register(String.class, JdbcType.VARCHAR, new StringTypeHandler());
		register(String.class, JdbcType.LONGVARCHAR, new ClobTypeHandler());
		register(String.class, JdbcType.NVARCHAR, new NStringTypeHandler());
		register(String.class, JdbcType.NCHAR, new NStringTypeHandler());
		register(String.class, JdbcType.NCLOB, new NClobTypeHandler());
		register(JdbcType.CHAR, new StringTypeHandler());
		register(JdbcType.VARCHAR, new StringTypeHandler());
		register(JdbcType.CLOB, new ClobTypeHandler());
		register(JdbcType.LONGVARCHAR, new ClobTypeHandler());
		register(JdbcType.NVARCHAR, new NStringTypeHandler());
		register(JdbcType.NCHAR, new NStringTypeHandler());
		register(JdbcType.NCLOB, new NClobTypeHandler());

		register(Object.class, JdbcType.ARRAY, new ArrayTypeHandler());
		register(JdbcType.ARRAY, new ArrayTypeHandler());

		register(BigInteger.class, new BigIntegerTypeHandler());
		register(JdbcType.BIGINT, new LongTypeHandler());

		register(BigDecimal.class, new BigDecimalTypeHandler());
		register(JdbcType.REAL, new BigDecimalTypeHandler());
		register(JdbcType.DECIMAL, new BigDecimalTypeHandler());
		register(JdbcType.NUMERIC, new BigDecimalTypeHandler());

		register(InputStream.class, new BlobInputStreamTypeHandler());
		register(Byte[].class, new ByteObjectArrayTypeHandler());
		register(Byte[].class, JdbcType.BLOB, new BlobByteObjectArrayTypeHandler());
		register(Byte[].class, JdbcType.LONGVARBINARY, new BlobByteObjectArrayTypeHandler());
		register(byte[].class, new ByteArrayTypeHandler());
		register(byte[].class, JdbcType.BLOB, new BlobTypeHandler());
		register(byte[].class, JdbcType.LONGVARBINARY, new BlobTypeHandler());
		register(JdbcType.LONGVARBINARY, new BlobTypeHandler());
		register(JdbcType.BLOB, new BlobTypeHandler());

		register(Object.class, UNKNOWN_TYPE_HANDLER);
		register(Object.class, JdbcType.OTHER, UNKNOWN_TYPE_HANDLER);
		register(JdbcType.OTHER, UNKNOWN_TYPE_HANDLER);

		register(Date.class, new DateTypeHandler());
		register(Date.class, JdbcType.DATE, new DateOnlyTypeHandler());
		register(Date.class, JdbcType.TIME, new TimeOnlyTypeHandler());
		register(JdbcType.TIMESTAMP, new DateTypeHandler());
		register(JdbcType.DATE, new DateOnlyTypeHandler());
		register(JdbcType.TIME, new TimeOnlyTypeHandler());

		register(java.sql.Date.class, new SqlDateTypeHandler());
		register(java.sql.Time.class, new SqlTimeTypeHandler());
		register(java.sql.Timestamp.class, new SqlTimestampTypeHandler());

		// mybatis-typehandlers-jsr310
		if (Jdk.dateAndTimeApiExists) {
			Java8TypeHandlersRegistrar.registerDateAndTimeHandlers(this);
		}

		// issue #273
		register(Character.class, new CharacterTypeHandler());
		register(char.class, new CharacterTypeHandler());
	}

	/**
	 * Set a default {@link TypeHandler} class for {@link Enum}. A default
	 * {@link TypeHandler} is {@link org.apache.ibatis.type.EnumTypeHandler}.
	 * 
	 * @param typeHandler
	 *            a type handler class for {@link Enum}
	 * @since 3.4.5
	 */
	public void setDefaultEnumTypeHandler(Class<? extends TypeHandler> typeHandler) {
		this.defaultEnumTypeHandler = typeHandler;
	}

	public boolean hasTypeHandler(Class<?> javaType) {
		return hasTypeHandler(javaType, null);
	}

	public boolean hasTypeHandler(TypeReference<?> javaTypeReference) {
		return hasTypeHandler(javaTypeReference, null);
	}

	public boolean hasTypeHandler(Class<?> javaType, JdbcType jdbcType) {
		return javaType != null && getTypeHandler((Type) javaType, jdbcType) != null;
	}

	public boolean hasTypeHandler(TypeReference<?> javaTypeReference, JdbcType jdbcType) {
		return javaTypeReference != null && getTypeHandler(javaTypeReference, jdbcType) != null;
	}

	public TypeHandler<?> getMappingTypeHandler(Class<? extends TypeHandler<?>> handlerType) {
		return ALL_TYPE_HANDLERS_MAP.get(handlerType);
	}

	public <T> TypeHandler<T> getTypeHandler(Class<T> type) {
		return getTypeHandler((Type) type, null);
	}

	public <T> TypeHandler<T> getTypeHandler(TypeReference<T> javaTypeReference) {
		return getTypeHandler(javaTypeReference, null);
	}

	public TypeHandler<?> getTypeHandler(JdbcType jdbcType) {
		return JDBC_TYPE_HANDLER_MAP.get(jdbcType);
	}

	public <T> TypeHandler<T> getTypeHandler(Class<T> type, JdbcType jdbcType) {
		return getTypeHandler((Type) type, jdbcType);
	}

	public <T> TypeHandler<T> getTypeHandler(TypeReference<T> javaTypeReference, JdbcType jdbcType) {
		return getTypeHandler(javaTypeReference.getRawType(), jdbcType);
	}
	
	//根据java类型和jdbcType类型查找对应的TypeHandler对象
	@SuppressWarnings("unchecked")
	private <T> TypeHandler<T> getTypeHandler(Type type, JdbcType jdbcType) {
		Map<JdbcType, TypeHandler<?>> jdbcHandlerMap = getJdbcHandlerMap(type);
		TypeHandler<?> handler = null;
		if (jdbcHandlerMap != null) {
			handler = jdbcHandlerMap.get(jdbcType);
			if (handler == null) {
				handler = jdbcHandlerMap.get(null);
			}
			//如果jdbcHandlerMap只注册了一个TypeHandler对象，则使用此对象
			if (handler == null) {
				// #591
				handler = pickSoleHandler(jdbcHandlerMap);
			}
		}
		// type drives generics here
		return (TypeHandler<T>) handler;
	}
	
	@SuppressWarnings({ "rawtypes", "unchecked" })
	private Map<JdbcType, TypeHandler<?>> getJdbcHandlerMap(Type type) {
		//查找java类型对应的TypeHandler集合
		Map<JdbcType, TypeHandler<?>> jdbcHandlerMap = TYPE_HANDLER_MAP.get(type);
		//检测是否为空集合标识
		if (NULL_TYPE_HANDLER_MAP.equals(jdbcHandlerMap)) {
			return null;
		}
		//如果集合为空
		if (jdbcHandlerMap == null && type instanceof Class) {
			Class<?> clazz = (Class<?>) type;
			//枚举类型处理
			if (clazz.isEnum()) {
				jdbcHandlerMap = getJdbcHandlerMapForEnumInterfaces(clazz, clazz);
				if (jdbcHandlerMap == null) {
					register(clazz, getInstance(clazz, defaultEnumTypeHandler));
					return TYPE_HANDLER_MAP.get(clazz);
				}
			} else {
				//查找父类对应的TypeHandler集合，并作为初始集合
				jdbcHandlerMap = getJdbcHandlerMapForSuperclass(clazz);
			}
		}
		//以空集合标识作为TypeHandler初始集合
		TYPE_HANDLER_MAP.put(type, jdbcHandlerMap == null ? NULL_TYPE_HANDLER_MAP : jdbcHandlerMap);
		return jdbcHandlerMap;
	}

	private Map<JdbcType, TypeHandler<?>> getJdbcHandlerMapForEnumInterfaces(Class<?> clazz, Class<?> enumClazz) {
		for (Class<?> iface : clazz.getInterfaces()) {
			Map<JdbcType, TypeHandler<?>> jdbcHandlerMap = TYPE_HANDLER_MAP.get(iface);
			if (jdbcHandlerMap == null) {
				jdbcHandlerMap = getJdbcHandlerMapForEnumInterfaces(iface, enumClazz);
			}
			if (jdbcHandlerMap != null) {
				// Found a type handler regsiterd to a super interface
				HashMap<JdbcType, TypeHandler<?>> newMap = new HashMap<JdbcType, TypeHandler<?>>();
				for (Entry<JdbcType, TypeHandler<?>> entry : jdbcHandlerMap.entrySet()) {
					// Create a type handler instance with enum type as a
					// constructor arg
					newMap.put(entry.getKey(), getInstance(enumClazz, entry.getValue().getClass()));
				}
				return newMap;
			}
		}
		return null;
	}
	
	//查找父类的TypeHandler集合
	private Map<JdbcType, TypeHandler<?>> getJdbcHandlerMapForSuperclass(Class<?> clazz) {
		Class<?> superclass = clazz.getSuperclass();
		if (superclass == null || Object.class.equals(superclass)) {
			return null;
		}
		Map<JdbcType, TypeHandler<?>> jdbcHandlerMap = TYPE_HANDLER_MAP.get(superclass);
		if (jdbcHandlerMap != null) {
			return jdbcHandlerMap;
		} else {
			return getJdbcHandlerMapForSuperclass(superclass);
		}
	}

	private TypeHandler<?> pickSoleHandler(Map<JdbcType, TypeHandler<?>> jdbcHandlerMap) {
		TypeHandler<?> soleHandler = null;
		for (TypeHandler<?> handler : jdbcHandlerMap.values()) {
			if (soleHandler == null) {
				soleHandler = handler;
			} else if (!handler.getClass().equals(soleHandler.getClass())) {
				// More than one type handlers registered.
				return null;
			}
		}
		return soleHandler;
	}

	public TypeHandler<Object> getUnknownTypeHandler() {
		return UNKNOWN_TYPE_HANDLER;
	}

	public void register(JdbcType jdbcType, TypeHandler<?> handler) {
		JDBC_TYPE_HANDLER_MAP.put(jdbcType, handler);
	}

	//
	// REGISTER INSTANCE
	//

	// Only handler

	@SuppressWarnings("unchecked")
	public <T> void register(TypeHandler<T> typeHandler) {
		boolean mappedTypeFound = false;
		//获取MappedTypes注解
		MappedTypes mappedTypes = typeHandler.getClass().getAnnotation(MappedTypes.class);
		if (mappedTypes != null) {
			for (Class<?> handledType : mappedTypes.value()) {
				register(handledType, typeHandler);
				mappedTypeFound = true;
			}
		}
		// @since 3.1.0 - try to auto-discover the mapped type
		//如果注解中指定了TypeHandler类型以及同时继承了TypeReference抽象类
		if (!mappedTypeFound && typeHandler instanceof TypeReference) {
			try {
				TypeReference<T> typeReference = (TypeReference<T>) typeHandler;
				register(typeReference.getRawType(), typeHandler);
				mappedTypeFound = true;
			} catch (Throwable t) {
				// maybe users define the TypeReference with a different type
				// and are not assignable, so just ignore it
			}
		}
		if (!mappedTypeFound) {
			register((Class<T>) null, typeHandler);
		}
	}

	// java type + handler
	//注册TypeHandler对象
	public <T> void register(Class<T> javaType, TypeHandler<? extends T> typeHandler) {
		register((Type) javaType, typeHandler);
	}

	private <T> void register(Type javaType, TypeHandler<? extends T> typeHandler) {
		//获取注解MappedJdbcTypes
		MappedJdbcTypes mappedJdbcTypes = typeHandler.getClass().getAnnotation(MappedJdbcTypes.class);
		//根据注解中指定的jdbc类型进行注册 
		if (mappedJdbcTypes != null) {
			for (JdbcType handledJdbcType : mappedJdbcTypes.value()) {
				register(javaType, handledJdbcType, typeHandler);
			}
			if (mappedJdbcTypes.includeNullJdbcType()) {
				register(javaType, null, typeHandler);
			}
		} else {
			register(javaType, null, typeHandler);
		}
	}

	public <T> void register(TypeReference<T> javaTypeReference, TypeHandler<? extends T> handler) {
		register(javaTypeReference.getRawType(), handler);
	}

	// java type + jdbc type + handler

	public <T> void register(Class<T> type, JdbcType jdbcType, TypeHandler<? extends T> handler) {
		register((Type) type, jdbcType, handler);
	}

	private void register(Type javaType, JdbcType jdbcType, TypeHandler<?> handler) {
		//检测是否明确指定了TypeHandler能够处理的java类型
		if (javaType != null) {
			//获取指定的java类型在TYPE_HANDLER_MAP集合中的对应的TypeHandler集合
			Map<JdbcType, TypeHandler<?>> map = TYPE_HANDLER_MAP.get(javaType);
			//如果集合为空创建新的TypeHandler集合并放到TYPE_HANDLER_MAP集合中
			if (map == null) {
				map = new HashMap<JdbcType, TypeHandler<?>>();
				TYPE_HANDLER_MAP.put(javaType, map);
			}
			//将TypeHandler对象注册到TYPE_HANDLER_MAP集合中
			map.put(jdbcType, handler);
		}
		//将ALL_TYPE_HANDLERS_MAP对象注册TypeHandler类型和对应的TypeHandler对象
		ALL_TYPE_HANDLERS_MAP.put(handler.getClass(), handler);
	}

	//
	// REGISTER CLASS
	//

	// Only handler type

	public void register(Class<?> typeHandlerClass) {
		boolean mappedTypeFound = false;
		//获取MappedTypes注解
		MappedTypes mappedTypes = typeHandlerClass.getAnnotation(MappedTypes.class);
		if (mappedTypes != null) {
			//根据注解中指定的java类型进行注册
			for (Class<?> javaTypeClass : mappedTypes.value()) {
				//经过强制类型转换和反射创建
				register(javaTypeClass, typeHandlerClass);
				mappedTypeFound = true;
			}
		}
		//如果未进行注解
		if (!mappedTypeFound) {
			register(getInstance(null, typeHandlerClass));
		}
	}

	// java type + handler type

	public void register(String javaTypeClassName, String typeHandlerClassName) throws ClassNotFoundException {
		register(Resources.classForName(javaTypeClassName), Resources.classForName(typeHandlerClassName));
	}

	public void register(Class<?> javaTypeClass, Class<?> typeHandlerClass) {
		register(javaTypeClass, getInstance(javaTypeClass, typeHandlerClass));
	}

	// java type + jdbc type + handler type

	public void register(Class<?> javaTypeClass, JdbcType jdbcType, Class<?> typeHandlerClass) {
		register(javaTypeClass, jdbcType, getInstance(javaTypeClass, typeHandlerClass));
	}

	// Construct a handler (used also from Builders)

	@SuppressWarnings("unchecked")
	public <T> TypeHandler<T> getInstance(Class<?> javaTypeClass, Class<?> typeHandlerClass) {
		if (javaTypeClass != null) {
			try {
				Constructor<?> c = typeHandlerClass.getConstructor(Class.class);
				return (TypeHandler<T>) c.newInstance(javaTypeClass);
			} catch (NoSuchMethodException ignored) {
				// ignored
			} catch (Exception e) {
				throw new TypeException("Failed invoking constructor for handler " + typeHandlerClass, e);
			}
		}
		try {
			Constructor<?> c = typeHandlerClass.getConstructor();
			return (TypeHandler<T>) c.newInstance();
		} catch (Exception e) {
			throw new TypeException("Unable to find a usable constructor for " + typeHandlerClass, e);
		}
	}

	// scan
	//主动扫描指定包下的TypeHandler实现类并进行注册
	public void register(String packageName) {
		ResolverUtil<Class<?>> resolverUtil = new ResolverUtil<Class<?>>();
		//查找指定包下的TypeHandler接口实现类
		resolverUtil.find(new ResolverUtil.IsA(TypeHandler.class), packageName);
		Set<Class<? extends Class<?>>> handlerSet = resolverUtil.getClasses();
		for (Class<?> type : handlerSet) {
			// Ignore inner classes and interfaces (including package-info.java)
			// and abstract classes
			//过滤掉接口、抽象类以及内部类
			if (!type.isAnonymousClass() && !type.isInterface() && !Modifier.isAbstract(type.getModifiers())) {
				register(type);
			}
		}
	}

	// get information

	/**
	 * @since 3.2.2
	 */
	public Collection<TypeHandler<?>> getTypeHandlers() {
		return Collections.unmodifiableCollection(ALL_TYPE_HANDLERS_MAP.values());
	}

}

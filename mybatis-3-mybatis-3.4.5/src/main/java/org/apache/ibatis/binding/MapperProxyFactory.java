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
package org.apache.ibatis.binding;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.ibatis.session.SqlSession;

/**
 * @author Lasse Voss 用于创建Mapper接口的对应代理对象
 */
public class MapperProxyFactory<T> {
	// Mapper接口对应的class对象
	private final Class<T> mapperInterface;
	// 缓存，key是mapperInterface接口中某个对应的Method对象，value是对应的MapperMethod对象
	private final Map<Method, MapperMethod> methodCache = new ConcurrentHashMap<Method, MapperMethod>();

	public MapperProxyFactory(Class<T> mapperInterface) {
		this.mapperInterface = mapperInterface;
	}

	public Class<T> getMapperInterface() {
		return mapperInterface;
	}

	public Map<Method, MapperMethod> getMethodCache() {
		return methodCache;
	}

	@SuppressWarnings("unchecked")
	protected T newInstance(MapperProxy<T> mapperProxy) {
		return (T) Proxy.newProxyInstance(mapperInterface.getClassLoader(), new Class[] { mapperInterface },
				mapperProxy);
	}
	
	//创建接口的代理对象
	public T newInstance(SqlSession sqlSession) {
		final MapperProxy<T> mapperProxy = new MapperProxy<T>(sqlSession, mapperInterface, methodCache);
		return newInstance(mapperProxy);
	}

}

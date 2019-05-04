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
package org.apache.ibatis.executor.keygen;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.binding.BindingException;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.ExecutorException;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;
import org.apache.ibatis.type.TypeHandlerRegistry;

/**
 * @author Clinton Begin
 * @author Kazuki Shimizu
 * 用于取回数据库生成的自增id，对应mybatis-config.xml配置文件
 * 中的useGeneratedKeys全局配置以及映射配置文件中sql节点的
 * useGeneratedKeys属性
 */
public class Jdbc3KeyGenerator implements KeyGenerator {

	/**
	 * A shared instance.
	 * 
	 * @since 3.4.3
	 */
	public static final Jdbc3KeyGenerator INSTANCE = new Jdbc3KeyGenerator();

	@Override
	public void processBefore(Executor executor, MappedStatement ms, Statement stmt, Object parameter) {
		// do nothing
	}

	@Override
	public void processAfter(Executor executor, MappedStatement ms, Statement stmt, Object parameter) {
		processBatch(ms, stmt, getParameters(parameter));
	}
	
	//将sql语句执行后生成的主键记录到用户传入的实参中，一般情况下，传入的实参是一个JavaBean或Map对象
	//则该对象对应一次插入操作的内容，如果是集合或数组，集合中一个元素对应一次插入操作
	public void processBatch(MappedStatement ms, Statement stmt, Collection<Object> parameters) {
		ResultSet rs = null;
		try {
			//获取数据库自动生成的主键，如果没有生成，则返回结果集为空
			rs = stmt.getGeneratedKeys();
			final Configuration configuration = ms.getConfiguration();
			final TypeHandlerRegistry typeHandlerRegistry = configuration.getTypeHandlerRegistry();
			//获取keyProperties属性指定的属性名称，它表示主键对应的属性名称
			final String[] keyProperties = ms.getKeyProperties();
			//获取ResultSet元数据信息
			final ResultSetMetaData rsmd = rs.getMetaData();
			TypeHandler<?>[] typeHandlers = null;
			//检测到数据库生成的主键的列数与keyProperties属性指定的列数是否匹配
			if (keyProperties != null && rsmd.getColumnCount() >= keyProperties.length) {
				for (Object parameter : parameters) {
					// there should be one row for each statement (also one for
					// each parameter)
					//parameters集合有多少个元素，就对应生成多少个主键
					if (!rs.next()) {
						break;
					}
					//为用户传入的实参创建相应的MetaObject对象
					final MetaObject metaParam = configuration.newMetaObject(parameter);
					if (typeHandlers == null) {
						typeHandlers = getTypeHandlers(typeHandlerRegistry, metaParam, keyProperties, rsmd);
					}
					//将主键设置到用户传入的参数对应的位置
					populateKeys(rs, metaParam, keyProperties, typeHandlers);
				}
			}
		} catch (Exception e) {
			throw new ExecutorException(
					"Error getting generated key or setting result to parameter object. Cause: " + e, e);
		} finally {
			if (rs != null) {
				try {
					rs.close();
				} catch (Exception e) {
					// ignore
				}
			}
		}
	}
	
	 
	private Collection<Object> getParameters(Object parameter) {
		Collection<Object> parameters = null;
		//参数为collection类型
		if (parameter instanceof Collection) {
			parameters = (Collection) parameter;
		} else if (parameter instanceof Map) {
			//参数为map类型，则获取其中指定的key
			Map parameterMap = (Map) parameter;
			if (parameterMap.containsKey("collection")) {
				parameters = (Collection) parameterMap.get("collection");
			} else if (parameterMap.containsKey("list")) {
				parameters = (List) parameterMap.get("list");
			} else if (parameterMap.containsKey("array")) {
				parameters = Arrays.asList((Object[]) parameterMap.get("array"));
			}
		}
		//参数为普通对象或不包含上述的key的map集合，则创建ArrayList
		if (parameters == null) {
			parameters = new ArrayList<Object>();
			parameters.add(parameter);
		}
		return parameters;
	}

	private TypeHandler<?>[] getTypeHandlers(TypeHandlerRegistry typeHandlerRegistry, MetaObject metaParam,
			String[] keyProperties, ResultSetMetaData rsmd) throws SQLException {
		TypeHandler<?>[] typeHandlers = new TypeHandler<?>[keyProperties.length];
		for (int i = 0; i < keyProperties.length; i++) {
			if (metaParam.hasSetter(keyProperties[i])) {
				TypeHandler<?> th;
				try {
					Class<?> keyPropertyType = metaParam.getSetterType(keyProperties[i]);
					th = typeHandlerRegistry.getTypeHandler(keyPropertyType,
							JdbcType.forCode(rsmd.getColumnType(i + 1)));
				} catch (BindingException e) {
					th = null;
				}
				typeHandlers[i] = th;
			}
		}
		return typeHandlers;
	}

	private void populateKeys(ResultSet rs, MetaObject metaParam, String[] keyProperties, TypeHandler<?>[] typeHandlers)
			throws SQLException {
		for (int i = 0; i < keyProperties.length; i++) {
			String property = keyProperties[i];
			TypeHandler<?> th = typeHandlers[i];
			if (th != null) {
				Object value = th.getResult(rs, i + 1);
				metaParam.setValue(property, value);
			}
		}
	}

}

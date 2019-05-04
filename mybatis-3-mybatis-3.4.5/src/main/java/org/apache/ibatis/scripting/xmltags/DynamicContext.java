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
package org.apache.ibatis.scripting.xmltags;

import java.util.HashMap;
import java.util.Map;

import ognl.OgnlContext;
import ognl.OgnlException;
import ognl.OgnlRuntime;
import ognl.PropertyAccessor;

import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.Configuration;

/**
 * @author Clinton Begin 记录解析动态sql语句之后产生的sql片段，可以看做是一个容器
 */
public class DynamicContext {

	public static final String PARAMETER_OBJECT_KEY = "_parameter";
	public static final String DATABASE_ID_KEY = "_databaseId";

	static {
		OgnlRuntime.setPropertyAccessor(ContextMap.class, new ContextAccessor());
	}
	
	//参数上下文
	private final ContextMap bindings;
	//在SqlNode解析动态sql时，会将解析后的SQL语句片段添加到该属性集合中保存，最终拼接出一条完整的sql语句
	private final StringBuilder sqlBuilder = new StringBuilder();
	private int uniqueNumber = 0;
	
	//构造方法初始化
	public DynamicContext(Configuration configuration, Object parameterObject) {
		//对于非Map类型的参数，会创建对应的MetaObject对象，并封装成ContextMap对象
		if (parameterObject != null && !(parameterObject instanceof Map)) {
			MetaObject metaObject = configuration.newMetaObject(parameterObject);
			bindings = new ContextMap(metaObject);
		} else {
			//初始化bindings集合
			bindings = new ContextMap(null);
		}
		//将PARAMETER_OBJECT_KEY->parameterObject这一对应关系添加到bindings集合中
		//其中PARAMETER_OBJECT_KEY的值为“_parameter”，在有的SqlNode实现中直接使用该字面值
		bindings.put(PARAMETER_OBJECT_KEY, parameterObject);
		bindings.put(DATABASE_ID_KEY, configuration.getDatabaseId());
	}

	public Map<String, Object> getBindings() {
		return bindings;
	}
	
	public void bind(String name, Object value) {
		bindings.put(name, value);
	}
	
	//追加sql片段
	public void appendSql(String sql) {
		sqlBuilder.append(sql);
		sqlBuilder.append(" ");
	}
	
	//获取解析后的完整sql语句
	public String getSql() {
		return sqlBuilder.toString().trim();
	}

	public int getUniqueNumber() {
		return uniqueNumber++;
	}
	
	//内部类
	static class ContextMap extends HashMap<String, Object> {
		private static final long serialVersionUID = 2977601501966151582L;
		//将用户传入的参数封装成MetaObject对象
		private MetaObject parameterMetaObject;

		public ContextMap(MetaObject parameterMetaObject) {
			this.parameterMetaObject = parameterMetaObject;
		}
		
		//重写get方法
		@Override
		public Object get(Object key) {
			String strKey = (String) key;
			//如果ContextMap中已经包含了key,则直接返回
			if (super.containsKey(strKey)) {
				return super.get(strKey);
			}
			
			//从运行时的参数查找对应属性
			if (parameterMetaObject != null) {
				// issue #61 do not modify the context when reading
				return parameterMetaObject.getValue(strKey);
			}

			return null;
		}
	}

	static class ContextAccessor implements PropertyAccessor {

		@Override
		public Object getProperty(Map context, Object target, Object name) throws OgnlException {
			Map map = (Map) target;

			Object result = map.get(name);
			if (map.containsKey(name) || result != null) {
				return result;
			}

			Object parameterObject = map.get(PARAMETER_OBJECT_KEY);
			if (parameterObject instanceof Map) {
				return ((Map) parameterObject).get(name);
			}

			return null;
		}

		@Override
		public void setProperty(Map context, Object target, Object name, Object value) throws OgnlException {
			Map<Object, Object> map = (Map<Object, Object>) target;
			map.put(name, value);
		}

		@Override
		public String getSourceAccessor(OgnlContext arg0, Object arg1, Object arg2) {
			return null;
		}

		@Override
		public String getSourceSetter(OgnlContext arg0, Object arg1, Object arg2) {
			return null;
		}
	}
}
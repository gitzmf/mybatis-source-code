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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import ognl.Ognl;
import ognl.OgnlException;

import org.apache.ibatis.builder.BuilderException;

/**
 * Caches OGNL parsed expressions.
 *
 * @author Eduardo Macarron
 *
 * @see <a href='http://code.google.com/p/mybatis/issues/detail?id=342'>Issue
 *      342</a>
 * 对原生的OGNL进行了封装
 */
public final class OgnlCache {
	//ognl表达式解析是比较耗时的，为了提高效率，使用了expressionCache字段对解析后的OGNL表达式进行了缓存
	private static final Map<String, Object> expressionCache = new ConcurrentHashMap<String, Object>();

	private OgnlCache() {
		// Prevent Instantiation of Static Class
	}

	public static Object getValue(String expression, Object root) {
		try {
			//创建OgnlContext对象，OgnlClassResolver替代了OGNL中原有的
			//DefaultClassResolver,使用的是前面介绍的Resource定位资源
			Map<Object, OgnlClassResolver> context = Ognl.createDefaultContext(root, new OgnlClassResolver());
		 	//使用OGNL执行expression表达式
			return Ognl.getValue(parseExpression(expression), context, root);
		} catch (OgnlException e) {
			throw new BuilderException("Error evaluating expression '" + expression + "'. Cause: " + e, e);
		}
	}

	private static Object parseExpression(String expression) throws OgnlException {
		//查找缓存
		Object node = expressionCache.get(expression);
		if (node == null) {
			//解析表达式
			node = Ognl.parseExpression(expression);
			//将表达式的解析结果添加到缓存中
			expressionCache.put(expression, node);
		}
		return node;
	}

}

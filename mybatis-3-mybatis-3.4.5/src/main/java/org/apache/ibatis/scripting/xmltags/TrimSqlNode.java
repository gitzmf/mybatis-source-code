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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringTokenizer;

import org.apache.ibatis.session.Configuration;

/**
 * @author Clinton Begin
 * TrimSqlNode会根据子节点的解析结果，添加或删除相应的前缀或后缀
 */
public class TrimSqlNode implements SqlNode {
	//trim节点的子节点
	private final SqlNode contents;
	//前缀
	private final String prefix;
	//后缀
	private final String suffix;
	//如果包括的是空sql语句，则删除前缀
	private final List<String> prefixesToOverride;
	//如果包括的是空sql语句，则删除后缀
	private final List<String> suffixesToOverride;
	private final Configuration configuration;

	public TrimSqlNode(Configuration configuration, SqlNode contents, String prefix, String prefixesToOverride,
			String suffix, String suffixesToOverride) {
		this(configuration, contents, prefix, parseOverrides(prefixesToOverride), suffix,
				parseOverrides(suffixesToOverride));
	}

	protected TrimSqlNode(Configuration configuration, SqlNode contents, String prefix, List<String> prefixesToOverride,
			String suffix, List<String> suffixesToOverride) {
		this.contents = contents;
		this.prefix = prefix;
		this.prefixesToOverride = prefixesToOverride;
		this.suffix = suffix;
		this.suffixesToOverride = suffixesToOverride;
		this.configuration = configuration;
	}

	@Override
	public boolean apply(DynamicContext context) {
		FilteredDynamicContext filteredDynamicContext = new FilteredDynamicContext(context);
		boolean result = contents.apply(filteredDynamicContext);
		//处理前缀和后缀
		filteredDynamicContext.applyAll();
		return result;
	}
	
	private static List<String> parseOverrides(String overrides) {
		if (overrides != null) {
			//按照"|"进行分割
			final StringTokenizer parser = new StringTokenizer(overrides, "|", false);
			final List<String> list = new ArrayList<String>(parser.countTokens());
			while (parser.hasMoreTokens()) {
				//转换成大写，并添加到集合中
				list.add(parser.nextToken().toUpperCase(Locale.ENGLISH));
			}
			return list;
		}
		return Collections.emptyList();
	}
	
	private class FilteredDynamicContext extends DynamicContext {
		private DynamicContext delegate;
		//是否已经处理前缀
		private boolean prefixApplied;
		private boolean suffixApplied;
		//记录子节点解析后的结果
		private StringBuilder sqlBuffer;

		public FilteredDynamicContext(DynamicContext delegate) {
			super(configuration, null);
			this.delegate = delegate;
			this.prefixApplied = false;
			this.suffixApplied = false;
			this.sqlBuffer = new StringBuilder();
		}
		
		public void applyAll() {
			//获取子节点解析后的结果，并全部转换为大写
			sqlBuffer = new StringBuilder(sqlBuffer.toString().trim());
			String trimmedUppercaseSql = sqlBuffer.toString().toUpperCase(Locale.ENGLISH);
			if (trimmedUppercaseSql.length() > 0) {
				applyPrefix(sqlBuffer, trimmedUppercaseSql);
				applySuffix(sqlBuffer, trimmedUppercaseSql);
			}
			delegate.appendSql(sqlBuffer.toString());
		}

		@Override
		public Map<String, Object> getBindings() {
			return delegate.getBindings();
		}

		@Override
		public void bind(String name, Object value) {
			delegate.bind(name, value);
		}

		@Override
		public int getUniqueNumber() {
			return delegate.getUniqueNumber();
		}

		@Override
		public void appendSql(String sql) {
			sqlBuffer.append(sql);
		}

		@Override
		public String getSql() {
			return delegate.getSql();
		}

		private void applyPrefix(StringBuilder sql, String trimmedUppercaseSql) {
			if (!prefixApplied) {
				prefixApplied = true;
				if (prefixesToOverride != null) {
					for (String toRemove : prefixesToOverride) {
						if (trimmedUppercaseSql.startsWith(toRemove)) {
							sql.delete(0, toRemove.trim().length());
							break;
						}
					}
				}
				if (prefix != null) {
					sql.insert(0, " ");
					sql.insert(0, prefix);
				}
			}
		}

		private void applySuffix(StringBuilder sql, String trimmedUppercaseSql) {
			if (!suffixApplied) {
				suffixApplied = true;
				if (suffixesToOverride != null) {
					for (String toRemove : suffixesToOverride) {
						if (trimmedUppercaseSql.endsWith(toRemove) || trimmedUppercaseSql.endsWith(toRemove.trim())) {
							int start = sql.length() - toRemove.trim().length();
							int end = sql.length();
							sql.delete(start, end);
							break;
						}
					}
				}
				if (suffix != null) {
					sql.append(" ");
					sql.append(suffix);
				}
			}
		}

	}

}

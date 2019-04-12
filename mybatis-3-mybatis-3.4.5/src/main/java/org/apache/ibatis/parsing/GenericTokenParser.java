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
package org.apache.ibatis.parsing;

/**
 * @author Clinton Begin 通用的占位符解析器
 */
public class GenericTokenParser {

	// 占位符开始标记
	private final String openToken;
	// 占位符结束标记
	private final String closeToken;
	// 标记处理器
	private final TokenHandler handler;

	public GenericTokenParser(String openToken, String closeToken, TokenHandler handler) {
		this.openToken = openToken;
		this.closeToken = closeToken;
		this.handler = handler;
	}

	// 解析到占位符的字面值，并将其讲给TokenHandler处理,然后将解析结果重新拼接成字符串返回
	public String parse(String text) {
		if (text == null || text.isEmpty()) {
			return "";
		}
		// search open token
		int start = text.indexOf(openToken, 0);
		if (start == -1) {
			// 没有找到直接返回
			return text;
		}
		char[] src = text.toCharArray();
		// 偏移量
		int offset = 0;
		// 记录解析后的字符串
		final StringBuilder builder = new StringBuilder();
		//记录查找到的占位符信息
		StringBuilder expression = null;
		while (start > -1) {
			// 判断开始标记符前边是否有转移字符，如果存在转义字符则移除转义字符
			if (start > 0 && src[start - 1] == '\\') {
				// this open token is escaped. remove the backslash and
				// continue.
				// 移除转义字符
				builder.append(src, offset, start - offset - 1).append(openToken);
				// 重新计算偏移量
				offset = start + openToken.length();
			} else {
				// 查找到开始标记且未转义
				// found open token. let's search close token.
				if (expression == null) {
					expression = new StringBuilder();
				} else {
					expression.setLength(0);
				}
				// 将前面的字符串追加到builder中
				builder.append(src, offset, start - offset);
				offset = start + openToken.length();

				// 从offset后继续查找结束标记
				int end = text.indexOf(closeToken, offset);
				while (end > -1) {
					if (end > offset && src[end - 1] == '\\') {
						// this close token is escaped. remove the backslash and
						// continue.
						expression.append(src, offset, end - offset - 1).append(closeToken);
						offset = end + closeToken.length();
						// 重新计算结束标识符的索引值
						end = text.indexOf(closeToken, offset);
					} else {
						expression.append(src, offset, end - offset);
						offset = end + closeToken.length();
						// 结束标记没有被找到跳出循环
						break;
					}
				}
				// 结束标记没有被找到
				if (end == -1) {
					// close token was not found.
					builder.append(src, start, src.length - start);
					offset = src.length;
				} else {
					// 找到了一组标记符，对该标记符进行值替换
					builder.append(handler.handleToken(expression.toString()));
					offset = end + closeToken.length();
				}
			}
			// 接着查找下一组标记符
			start = text.indexOf(openToken, offset);
		}
		//查找完占位符后，直接追加后面的
		if (offset < src.length) {
			builder.append(src, offset, src.length - offset);
		}
		return builder.toString();
	}
}

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
package org.apache.ibatis.reflection.property;

import java.util.Locale;

import org.apache.ibatis.reflection.ReflectionException;

/**
 * @author Clinton Begin 方法名到属性名的转换，以及多种检测操作
 */
public final class PropertyNamer {

	private PropertyNamer() {
		// Prevent Instantiation of Static Class
	}

	// 转换
	public static String methodToProperty(String name) {
		// is开头的方法，截取
		if (name.startsWith("is")) {
			name = name.substring(2);
			// get或set开头的方法，截取
		} else if (name.startsWith("get") || name.startsWith("set")) {
			name = name.substring(3);
		} else {
			throw new ReflectionException(
					"Error parsing property name '" + name + "'.  Didn't start with 'is', 'get' or 'set'.");
		}

		// 首字母小写
		if (name.length() == 1 || (name.length() > 1 && !Character.isUpperCase(name.charAt(1)))) {
			name = name.substring(0, 1).toLowerCase(Locale.ENGLISH) + name.substring(1);
		}

		return name;
	}

	// 检测方法名是否对应属性名
	public static boolean isProperty(String name) {
		return name.startsWith("get") || name.startsWith("set") || name.startsWith("is");
	}

	// 检测方法是否为getter方法
	public static boolean isGetter(String name) {
		return name.startsWith("get") || name.startsWith("is");
	}

	// 检测方法是否为setter方法
	public static boolean isSetter(String name) {
		return name.startsWith("set");
	}

}

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

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * @author Clinton Begin 类型处理器
 */
public interface TypeHandler<T> {
	//在PreparedStatement为sql语句绑定参数的时候，从jsbcType类型转换为java类型
	void setParameter(PreparedStatement ps, int i, T parameter, JdbcType jdbcType) throws SQLException;
	//在ResultSet中获取数据的时候，从java类型转换为jdbcType类型
	T getResult(ResultSet rs, String columnName) throws SQLException;

	T getResult(ResultSet rs, int columnIndex) throws SQLException;

	T getResult(CallableStatement cs, int columnIndex) throws SQLException;

}

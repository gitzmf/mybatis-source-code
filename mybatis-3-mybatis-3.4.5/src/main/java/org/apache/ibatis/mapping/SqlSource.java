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
package org.apache.ibatis.mapping;

/**
 * Represents the content of a mapped statement read from an XML file or an
 * annotation. It creates the SQL that will be passed to the database out of the
 * input parameter received from the user.
 *
 * @author Clinton Begin
 * 表示映射配置文件或是注解中定义的SQL语句，但是是不能被数据库执行的
 * 里面可能含有动态的SQL语句相关节点或是占位符需要解析的元素
 * 
 */
public interface SqlSource {
	//会根据映射文件或是注解描述的sql语句，以及传入的参数，返回可执行的sql语句
	BoundSql getBoundSql(Object parameterObject);

}

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
package org.apache.ibatis.datasource.unpooled;

import java.util.Properties;

import javax.sql.DataSource;

import org.apache.ibatis.datasource.DataSourceException;
import org.apache.ibatis.datasource.DataSourceFactory;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;

/**
 * @author Clinton Begin
 *         UnpooledDataSourceFactory工厂类用于创建UnpooledDataSource对象并初始化其字段dataSource
 */
public class UnpooledDataSourceFactory implements DataSourceFactory {

	private static final String DRIVER_PROPERTY_PREFIX = "driver.";
	private static final int DRIVER_PROPERTY_PREFIX_LENGTH = DRIVER_PROPERTY_PREFIX.length();

	protected DataSource dataSource;

	public UnpooledDataSourceFactory() {
		this.dataSource = new UnpooledDataSource();
	}

	// 对UnpooledDataSource对象进行配置
	@Override
	public void setProperties(Properties properties) {
		Properties driverProperties = new Properties();
		// 创建dataSource对象对应的MetaObject对象
		MetaObject metaDataSource = SystemMetaObject.forObject(dataSource);
		// 遍历properties集合，该集合中配置了有关数据源配置的信息
		for (Object key : properties.keySet()) {
			String propertyName = (String) key;
			// 判断以"driver."开头的配置项是对DataSource的配置记录到driverProperties对象中
			if (propertyName.startsWith(DRIVER_PROPERTY_PREFIX)) {
				String value = properties.getProperty(propertyName);
				driverProperties.setProperty(propertyName.substring(DRIVER_PROPERTY_PREFIX_LENGTH), value);
			} else if (metaDataSource.hasSetter(propertyName)) {
				String value = (String) properties.get(propertyName);
				// 根据属性的类型进行类型转换
				Object convertedValue = convertValue(metaDataSource, propertyName, value);
				// 设置datasource相关的属性值
				metaDataSource.setValue(propertyName, convertedValue);
			} else {
				throw new DataSourceException("Unknown DataSource property: " + propertyName);
			}
		}
		//设置DataSource.driverProperties的属性值
		if (driverProperties.size() > 0) {
			metaDataSource.setValue("driverProperties", driverProperties);
		}
	}

	@Override
	public DataSource getDataSource() {
		return dataSource;
	}

	private Object convertValue(MetaObject metaDataSource, String propertyName, String value) {
		Object convertedValue = value;
		Class<?> targetType = metaDataSource.getSetterType(propertyName);
		if (targetType == Integer.class || targetType == int.class) {
			convertedValue = Integer.valueOf(value);
		} else if (targetType == Long.class || targetType == long.class) {
			convertedValue = Long.valueOf(value);
		} else if (targetType == Boolean.class || targetType == boolean.class) {
			convertedValue = Boolean.valueOf(value);
		}
		return convertedValue;
	}

}

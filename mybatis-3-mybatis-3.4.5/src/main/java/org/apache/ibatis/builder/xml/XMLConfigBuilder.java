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
package org.apache.ibatis.builder.xml;

import java.io.InputStream;
import java.io.Reader;
import java.util.Properties;
import javax.sql.DataSource;

import org.apache.ibatis.builder.BaseBuilder;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.datasource.DataSourceFactory;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.loader.ProxyFactory;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.io.VFS;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.mapping.DatabaseIdProvider;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.parsing.XPathParser;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.reflection.DefaultReflectorFactory;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.reflection.ReflectorFactory;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.reflection.wrapper.ObjectWrapperFactory;
import org.apache.ibatis.session.AutoMappingBehavior;
import org.apache.ibatis.session.AutoMappingUnknownColumnBehavior;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.transaction.TransactionFactory;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;

/**
 * @author Clinton Begin
 * @author Kazuki Shimizu
 * 负责解析mybatis-config.xml配置文件
 */
public class XMLConfigBuilder extends BaseBuilder {
	//标识是否解析过mybatis-config.xml配置文件
	private boolean parsed;
	//用于解析mybatis-config.xml配置文件的XPathParser对象
	private final XPathParser parser;
	//标识<environment>标签配置的名称，默认读取<environment>标签的default属性
	private String environment;
	//ReflectorFactory负责创建和缓存Reflector对象
	private final ReflectorFactory localReflectorFactory = new DefaultReflectorFactory();

	public XMLConfigBuilder(Reader reader) {
		this(reader, null, null);
	}

	public XMLConfigBuilder(Reader reader, String environment) {
		this(reader, environment, null);
	}

	public XMLConfigBuilder(Reader reader, String environment, Properties props) {
		this(new XPathParser(reader, true, props, new XMLMapperEntityResolver()), environment, props);
	}

	public XMLConfigBuilder(InputStream inputStream) {
		this(inputStream, null, null);
	}

	public XMLConfigBuilder(InputStream inputStream, String environment) {
		this(inputStream, environment, null);
	}

	public XMLConfigBuilder(InputStream inputStream, String environment, Properties props) {
		this(new XPathParser(inputStream, true, props, new XMLMapperEntityResolver()), environment, props);
	}

	private XMLConfigBuilder(XPathParser parser, String environment, Properties props) {
		super(new Configuration());
		ErrorContext.instance().resource("SQL Mapper Configuration");
		this.configuration.setVariables(props);
		this.parsed = false;
		this.environment = environment;
		this.parser = parser;
	}
	
	//解析mybatis-config.xml配置文件的入口
	public Configuration parse() {
		//根据pased的值，判断解析是否完成
		if (parsed) {
			throw new BuilderException("Each XMLConfigBuilder can only be used once.");
		}
		parsed = true;
		//实现解析整个过程的方法
		parseConfiguration(parser.evalNode("/configuration"));
		return configuration;
	}
	
	//核心方法
	private void parseConfiguration(XNode root) {
		try {
			// issue #117 read properties first
			//解析properties节点
			propertiesElement(root.evalNode("properties"));
			//解析settings节点
			Properties settings = settingsAsProperties(root.evalNode("settings"));
			loadCustomVfs(settings);
			//解析typeAliases节点
			typeAliasesElement(root.evalNode("typeAliases"));
			//解析plugins节点
			pluginElement(root.evalNode("plugins"));
			//解析objectFactory节点
			objectFactoryElement(root.evalNode("objectFactory"));
			//解析objectWrapperFactory节点
			objectWrapperFactoryElement(root.evalNode("objectWrapperFactory"));
			//解析reflectorFactory节点
			reflectorFactoryElement(root.evalNode("reflectorFactory"));
			settingsElement(settings);
			// read it after objectFactory and objectWrapperFactory issue #631
			//解析environments节点
			environmentsElement(root.evalNode("environments"));
			//解析databaseIdProvider节点
			databaseIdProviderElement(root.evalNode("databaseIdProvider"));
			//解析typeHandlers节点
			typeHandlerElement(root.evalNode("typeHandlers"));
			//解析mappers节点
			mapperElement(root.evalNode("mappers"));
		} catch (Exception e) {
			throw new BuilderException("Error parsing SQL Mapper Configuration. Cause: " + e, e);
		}
	}
	
	//解析settings节点
	private Properties settingsAsProperties(XNode context) {
		if (context == null) {
			return new Properties();
		}
		//解析settings子节点的name和value属性，并返回properties对象
		Properties props = context.getChildrenAsProperties();
		// Check that all settings are known to the configuration class
		//创建Configuration对应的MetaClass对象
		MetaClass metaConfig = MetaClass.forClass(Configuration.class, localReflectorFactory);
		for (Object key : props.keySet()) {
			//检测Configuration中是否定义了key指定属性相应的setter方法
			if (!metaConfig.hasSetter(String.valueOf(key))) {
				throw new BuilderException(
						"The setting " + key + " is not known.  Make sure you spelled it correctly (case sensitive).");
			}
		}
		return props;
	}

	private void loadCustomVfs(Properties props) throws ClassNotFoundException {
		String value = props.getProperty("vfsImpl");
		if (value != null) {
			String[] clazzes = value.split(",");
			for (String clazz : clazzes) {
				if (!clazz.isEmpty()) {
					@SuppressWarnings("unchecked")
					Class<? extends VFS> vfsImpl = (Class<? extends VFS>) Resources.classForName(clazz);
					configuration.setVfsImpl(vfsImpl);
				}
			}
		}
	}
	
	//解析typeAliases节点
	private void typeAliasesElement(XNode parent) {
		if (parent != null) {
			//处理全部节点
			for (XNode child : parent.getChildren()) {
				//处理package节点
				if ("package".equals(child.getName())) {
					//获取指定包名
					String typeAliasPackage = child.getStringAttribute("name");
					//通过别名注册器扫描指定包中的所有类，并解析@alias注解，完成别名的注册
					configuration.getTypeAliasRegistry().registerAliases(typeAliasPackage);
				} else {
					//处理typeAlias节点
					//获取指定别名
					String alias = child.getStringAttribute("alias");
					//获取别名对应的类型
					String type = child.getStringAttribute("type");
					try {
						Class<?> clazz = Resources.classForName(type);
						//完成别名的注册
						if (alias == null) {
							//扫描注解，完成注册
							typeAliasRegistry.registerAlias(clazz);
						} else {
							//注册别名
							typeAliasRegistry.registerAlias(alias, clazz);
						}
					} catch (ClassNotFoundException e) {
						throw new BuilderException("Error registering typeAlias for '" + alias + "'. Cause: " + e, e);
					}
				}
			}
		}
	}
	
	//解析plugins节点
	private void pluginElement(XNode parent) throws Exception {
		if (parent != null) {
			//遍历全部子节点plugin
			for (XNode child : parent.getChildren()) {
				//获取子节点plugin中属性interceptor属性的值
				String interceptor = child.getStringAttribute("interceptor");
				//获取plugin节点下的properties配置信息，并形成properties对象
				Properties properties = child.getChildrenAsProperties();
				//通过别名注册器解析别名后，实例化Interceptor对象
				Interceptor interceptorInstance = (Interceptor) resolveClass(interceptor).newInstance();
				//设置Interceptor对象的属性
				interceptorInstance.setProperties(properties);
				//记录Interceptor对象
				configuration.addInterceptor(interceptorInstance);
			}
		}
	}
	
	//解析objectFactory节点指定的objectFactory实现类并实例化，最后记录到Configuration.objectFactory字段中
	private void objectFactoryElement(XNode context) throws Exception {
		if (context != null) {
			//获取objectFactory的type属性
			String type = context.getStringAttribute("type");
			//获取objectFactory的节点下配置信息，并形成properties对象
			Properties properties = context.getChildrenAsProperties();
			//进行别名解析后，实例化自定义的ObjectFactory实现
			ObjectFactory factory = (ObjectFactory) resolveClass(type).newInstance();
			//设置自定义的ObjectFactory属性，完成初始化操作
			factory.setProperties(properties);
			//将自定义的ObjectFactory对象记录到Configuration对象的objectfactory字段中
			configuration.setObjectFactory(factory);
		}
	}

	private void objectWrapperFactoryElement(XNode context) throws Exception {
		if (context != null) {
			String type = context.getStringAttribute("type");
			ObjectWrapperFactory factory = (ObjectWrapperFactory) resolveClass(type).newInstance();
			configuration.setObjectWrapperFactory(factory);
		}
	}

	private void reflectorFactoryElement(XNode context) throws Exception {
		if (context != null) {
			String type = context.getStringAttribute("type");
			ReflectorFactory factory = (ReflectorFactory) resolveClass(type).newInstance();
			configuration.setReflectorFactory(factory);
		}
	}
	
	//解析properties节点
	private void propertiesElement(XNode context) throws Exception {
		if (context != null) {
			//解析properties标签的name和value属性，并记录到properties中
			Properties defaults = context.getChildrenAsProperties();
			//解析properties的resource和url属性，用于确定properties配置文件的位置
			String resource = context.getStringAttribute("resource");
			String url = context.getStringAttribute("url");
			//不能同时存在，否则抛出异常
			if (resource != null && url != null) {
				throw new BuilderException(
						"The properties element cannot specify both a URL and a resource based property file reference.  Please specify one or the other.");
			}
			//加载resource或url指定的配置文件
			if (resource != null) {
				defaults.putAll(Resources.getResourceAsProperties(resource));
			} else if (url != null) {
				defaults.putAll(Resources.getUrlAsProperties(url));
			}
			//与Configuration对象的variables集合进行合并
			Properties vars = configuration.getVariables();
			if (vars != null) {
				defaults.putAll(vars);
			}
			//更新XPathParser和Configuration的variables字段
			parser.setVariables(defaults);
			configuration.setVariables(defaults);
		}
	}
	
	 
	private void settingsElement(Properties props) throws Exception {
		configuration.setAutoMappingBehavior(
				AutoMappingBehavior.valueOf(props.getProperty("autoMappingBehavior", "PARTIAL")));
		configuration.setAutoMappingUnknownColumnBehavior(AutoMappingUnknownColumnBehavior
				.valueOf(props.getProperty("autoMappingUnknownColumnBehavior", "NONE")));
		configuration.setCacheEnabled(booleanValueOf(props.getProperty("cacheEnabled"), true));
		configuration.setProxyFactory((ProxyFactory) createInstance(props.getProperty("proxyFactory")));
		configuration.setLazyLoadingEnabled(booleanValueOf(props.getProperty("lazyLoadingEnabled"), false));
		configuration.setAggressiveLazyLoading(booleanValueOf(props.getProperty("aggressiveLazyLoading"), false));
		configuration
				.setMultipleResultSetsEnabled(booleanValueOf(props.getProperty("multipleResultSetsEnabled"), true));
		configuration.setUseColumnLabel(booleanValueOf(props.getProperty("useColumnLabel"), true));
		configuration.setUseGeneratedKeys(booleanValueOf(props.getProperty("useGeneratedKeys"), false));
		configuration.setDefaultExecutorType(ExecutorType.valueOf(props.getProperty("defaultExecutorType", "SIMPLE")));
		configuration.setDefaultStatementTimeout(integerValueOf(props.getProperty("defaultStatementTimeout"), null));
		configuration.setDefaultFetchSize(integerValueOf(props.getProperty("defaultFetchSize"), null));
		configuration.setMapUnderscoreToCamelCase(booleanValueOf(props.getProperty("mapUnderscoreToCamelCase"), false));
		configuration.setSafeRowBoundsEnabled(booleanValueOf(props.getProperty("safeRowBoundsEnabled"), false));
		configuration.setLocalCacheScope(LocalCacheScope.valueOf(props.getProperty("localCacheScope", "SESSION")));
		configuration.setJdbcTypeForNull(JdbcType.valueOf(props.getProperty("jdbcTypeForNull", "OTHER")));
		configuration.setLazyLoadTriggerMethods(
				stringSetValueOf(props.getProperty("lazyLoadTriggerMethods"), "equals,clone,hashCode,toString"));
		configuration.setSafeResultHandlerEnabled(booleanValueOf(props.getProperty("safeResultHandlerEnabled"), true));
		configuration.setDefaultScriptingLanguage(resolveClass(props.getProperty("defaultScriptingLanguage")));
		@SuppressWarnings("unchecked")
		Class<? extends TypeHandler> typeHandler = (Class<? extends TypeHandler>) resolveClass(
				props.getProperty("defaultEnumTypeHandler"));
		configuration.setDefaultEnumTypeHandler(typeHandler);
		configuration.setCallSettersOnNulls(booleanValueOf(props.getProperty("callSettersOnNulls"), false));
		configuration.setUseActualParamName(booleanValueOf(props.getProperty("useActualParamName"), true));
		configuration
				.setReturnInstanceForEmptyRow(booleanValueOf(props.getProperty("returnInstanceForEmptyRow"), false));
		configuration.setLogPrefix(props.getProperty("logPrefix"));
		@SuppressWarnings("unchecked")
		Class<? extends Log> logImpl = (Class<? extends Log>) resolveClass(props.getProperty("logImpl"));
		configuration.setLogImpl(logImpl);
		configuration.setConfigurationFactory(resolveClass(props.getProperty("configurationFactory")));
	}
	
	//解析environment节点
	private void environmentsElement(XNode context) throws Exception {
		if (context != null) {
			//如果没有配置environment字段，则使用default属性指定的environment
			if (environment == null) {
				environment = context.getStringAttribute("default");
			}
			//遍历environment节点
			for (XNode child : context.getChildren()) {
				String id = child.getStringAttribute("id");
				//与XMLConfigBuilder.environment字段进行匹配
				if (isSpecifiedEnvironment(id)) {
					//实例化TransactionFactory，具体实现是通过别名注册器解析别名后，实例化的
					TransactionFactory txFactory = transactionManagerElement(child.evalNode("transactionManager"));
					//创建DataSourceFactory和DataSource
					DataSourceFactory dsFactory = dataSourceElement(child.evalNode("dataSource"));
					DataSource dataSource = dsFactory.getDataSource();
					//创建Environment,里面封装了TransactionFactory、DataSource对象
					Environment.Builder environmentBuilder = new Environment.Builder(id).transactionFactory(txFactory)
							.dataSource(dataSource);
					//记录到Configuration的environment字段中
					configuration.setEnvironment(environmentBuilder.build());
				}
			}
		}
	}
	
	//解析databaseId节点，主要用来识别sql对不同数据库产品的支持
	private void databaseIdProviderElement(XNode context) throws Exception {
		DatabaseIdProvider databaseIdProvider = null;
		if (context != null) {
			String type = context.getStringAttribute("type");
			// awful patch to keep backward compatibility
			//为了保证兼容性，修改了type的值
			if ("VENDOR".equals(type)) {
				type = "DB_VENDOR";
			}
			//解析相关的配置信息
			Properties properties = context.getChildrenAsProperties();
			//创建DatabaseIdProvider对象
			databaseIdProvider = (DatabaseIdProvider) resolveClass(type).newInstance();
			//设置配置信息，完成初始化
			databaseIdProvider.setProperties(properties);
		}
		Environment environment = configuration.getEnvironment();
		if (environment != null && databaseIdProvider != null) {
			//通过前面的Datasource获取databaseid,并记录到Configuration的databaseid字段中
			String databaseId = databaseIdProvider.getDatabaseId(environment.getDataSource());
			configuration.setDatabaseId(databaseId);
		}
	}

	private TransactionFactory transactionManagerElement(XNode context) throws Exception {
		if (context != null) {
			String type = context.getStringAttribute("type");
			Properties props = context.getChildrenAsProperties();
			TransactionFactory factory = (TransactionFactory) resolveClass(type).newInstance();
			factory.setProperties(props);
			return factory;
		}
		throw new BuilderException("Environment declaration requires a TransactionFactory.");
	}

	private DataSourceFactory dataSourceElement(XNode context) throws Exception {
		if (context != null) {
			String type = context.getStringAttribute("type");
			Properties props = context.getChildrenAsProperties();
			DataSourceFactory factory = (DataSourceFactory) resolveClass(type).newInstance();
			factory.setProperties(props);
			return factory;
		}
		throw new BuilderException("Environment declaration requires a DataSourceFactory.");
	}

	private void typeHandlerElement(XNode parent) throws Exception {
		if (parent != null) {
			for (XNode child : parent.getChildren()) {
				if ("package".equals(child.getName())) {
					String typeHandlerPackage = child.getStringAttribute("name");
					typeHandlerRegistry.register(typeHandlerPackage);
				} else {
					String javaTypeName = child.getStringAttribute("javaType");
					String jdbcTypeName = child.getStringAttribute("jdbcType");
					String handlerTypeName = child.getStringAttribute("handler");
					Class<?> javaTypeClass = resolveClass(javaTypeName);
					JdbcType jdbcType = resolveJdbcType(jdbcTypeName);
					Class<?> typeHandlerClass = resolveClass(handlerTypeName);
					if (javaTypeClass != null) {
						if (jdbcType == null) {
							typeHandlerRegistry.register(javaTypeClass, typeHandlerClass);
						} else {
							typeHandlerRegistry.register(javaTypeClass, jdbcType, typeHandlerClass);
						}
					} else {
						typeHandlerRegistry.register(typeHandlerClass);
					}
				}
			}
		}
	}
	
	//解析mapper节点，创建XMLMapperBuilder对象加载映射文件，如果映射文件存在对应的Mapper接口，也会加载Mapper接口
	//解析其中的注解，并在映射注册器中注册
	private void mapperElement(XNode parent) throws Exception {
		if (parent != null) {
			//处理mapper子节点
			for (XNode child : parent.getChildren()) {
				//package子节点
				if ("package".equals(child.getName())) {
					String mapperPackage = child.getStringAttribute("name");
					//扫描指定的包，并向mapper注册器中注册mapper接口
					configuration.addMappers(mapperPackage);
				} else {
					//获取mapper节点的resource、url、class属性，互相排斥
					String resource = child.getStringAttribute("resource");
					String url = child.getStringAttribute("url");
					String mapperClass = child.getStringAttribute("class");
					if (resource != null && url == null && mapperClass == null) {
						ErrorContext.instance().resource(resource);
						InputStream inputStream = Resources.getResourceAsStream(resource);
						//创建XMLMapperBuilder对象，解析映射配置文件
						XMLMapperBuilder mapperParser = new XMLMapperBuilder(inputStream, configuration, resource,
								configuration.getSqlFragments());
						mapperParser.parse();
					} else if (resource == null && url != null && mapperClass == null) {
						ErrorContext.instance().resource(url);
						InputStream inputStream = Resources.getUrlAsStream(url);
						XMLMapperBuilder mapperParser = new XMLMapperBuilder(inputStream, configuration, url,
								configuration.getSqlFragments());
						mapperParser.parse();
					} else if (resource == null && url == null && mapperClass != null) {
						Class<?> mapperInterface = Resources.classForName(mapperClass);
						configuration.addMapper(mapperInterface);
					} else {
						throw new BuilderException(
								"A mapper element may only specify a url, resource or class, but not more than one.");
					}
				}
			}
		}
	}

	private boolean isSpecifiedEnvironment(String id) {
		if (environment == null) {
			throw new BuilderException("No environment specified.");
		} else if (id == null) {
			throw new BuilderException("Environment requires an id attribute.");
		} else if (environment.equals(id)) {
			return true;
		}
		return false;
	}

}

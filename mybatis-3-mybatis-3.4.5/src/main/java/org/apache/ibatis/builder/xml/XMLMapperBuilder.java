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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.ibatis.builder.BaseBuilder;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.builder.CacheRefResolver;
import org.apache.ibatis.builder.IncompleteElementException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.builder.ResultMapResolver;
import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Discriminator;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.mapping.ResultFlag;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.parsing.XPathParser;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;

/**
 * @author Clinton Begin 负责解析映射配置文件，也是具体的建造者对象
 */
public class XMLMapperBuilder extends BaseBuilder {

	private final XPathParser parser;
	private final MapperBuilderAssistant builderAssistant;
	private final Map<String, XNode> sqlFragments;
	private final String resource;

	@Deprecated
	public XMLMapperBuilder(Reader reader, Configuration configuration, String resource,
			Map<String, XNode> sqlFragments, String namespace) {
		this(reader, configuration, resource, sqlFragments);
		this.builderAssistant.setCurrentNamespace(namespace);
	}

	@Deprecated
	public XMLMapperBuilder(Reader reader, Configuration configuration, String resource,
			Map<String, XNode> sqlFragments) {
		this(new XPathParser(reader, true, configuration.getVariables(), new XMLMapperEntityResolver()), configuration,
				resource, sqlFragments);
	}

	public XMLMapperBuilder(InputStream inputStream, Configuration configuration, String resource,
			Map<String, XNode> sqlFragments, String namespace) {
		this(inputStream, configuration, resource, sqlFragments);
		this.builderAssistant.setCurrentNamespace(namespace);
	}

	public XMLMapperBuilder(InputStream inputStream, Configuration configuration, String resource,
			Map<String, XNode> sqlFragments) {
		this(new XPathParser(inputStream, true, configuration.getVariables(), new XMLMapperEntityResolver()),
				configuration, resource, sqlFragments);
	}

	private XMLMapperBuilder(XPathParser parser, Configuration configuration, String resource,
			Map<String, XNode> sqlFragments) {
		super(configuration);
		this.builderAssistant = new MapperBuilderAssistant(configuration, resource);
		this.parser = parser;
		this.sqlFragments = sqlFragments;
		this.resource = resource;
	}
	
	//解析映射配置文件的入口
	public void parse() {
		//判断是否加载过该映射配置文件
		if (!configuration.isResourceLoaded(resource)) {
			//处理mapper节点
			configurationElement(parser.evalNode("/mapper"));
			//将resource添加到Configuration.loadResources集合中保存
			//它是HashSet<String>类型的集合，其中记录已经加载过的银蛇文件
			configuration.addLoadedResource(resource);
			//注册Mapper接口
			bindMapperForNamespace();
		}
		
		//处理解析失败的resultMap节点
		parsePendingResultMaps();
		//处理解析失败的-ref节点
		parsePendingCacheRefs();
		//处理解析失败的sql语句节点
		parsePendingStatements();
	}

	public XNode getSqlFragment(String refid) {
		return sqlFragments.get(refid);
	}
	
	//解析映射文件的核心方法
	private void configurationElement(XNode context) {
		try {
			//获取mapper节点的namespace属性
			String namespace = context.getStringAttribute("namespace");
			//如果namespace为空,则抛出异常
			if (namespace == null || namespace.equals("")) {
				throw new BuilderException("Mapper's namespace cannot be empty");
			}
			//设置MapperBuilderAssistant的currentNamespace字段，记录当前命名空间
			builderAssistant.setCurrentNamespace(namespace);
			//解析映射文件中相应的节点
			cacheRefElement(context.evalNode("cache-ref"));
			cacheElement(context.evalNode("cache"));
			parameterMapElement(context.evalNodes("/mapper/parameterMap"));
			resultMapElements(context.evalNodes("/mapper/resultMap"));
			sqlElement(context.evalNodes("/mapper/sql"));
			buildStatementFromContext(context.evalNodes("select|insert|update|delete"));
		} catch (Exception e) {
			throw new BuilderException("Error parsing Mapper XML. Cause: " + e, e);
		}
	}

	private void buildStatementFromContext(List<XNode> list) {
		if (configuration.getDatabaseId() != null) {
			buildStatementFromContext(list, configuration.getDatabaseId());
		}
		buildStatementFromContext(list, null);
	}

	private void buildStatementFromContext(List<XNode> list, String requiredDatabaseId) {
		for (XNode context : list) {
			final XMLStatementBuilder statementParser = new XMLStatementBuilder(configuration, builderAssistant,
					context, requiredDatabaseId);
			try {
				statementParser.parseStatementNode();
			} catch (IncompleteElementException e) {
				configuration.addIncompleteStatement(statementParser);
			}
		}
	}
	
	
	private void parsePendingResultMaps() {
		Collection<ResultMapResolver> incompleteResultMaps = configuration.getIncompleteResultMaps();
		synchronized (incompleteResultMaps) {
			Iterator<ResultMapResolver> iter = incompleteResultMaps.iterator();
			while (iter.hasNext()) {
				try {
					iter.next().resolve();
					iter.remove();
				} catch (IncompleteElementException e) {
					// ResultMap is still missing a resource...
				}
			}
		}
	}

	private void parsePendingCacheRefs() {
		Collection<CacheRefResolver> incompleteCacheRefs = configuration.getIncompleteCacheRefs();
		synchronized (incompleteCacheRefs) {
			Iterator<CacheRefResolver> iter = incompleteCacheRefs.iterator();
			while (iter.hasNext()) {
				try {
					iter.next().resolveCacheRef();
					iter.remove();
				} catch (IncompleteElementException e) {
					// Cache ref is still missing a resource...
				}
			}
		}
	}
	
	 
	private void parsePendingStatements() {
		//获取Configuration.incompleteStatements集合
		Collection<XMLStatementBuilder> incompleteStatements = configuration.getIncompleteStatements();
		//加锁同步
		synchronized (incompleteStatements) {
			//遍历incompleteStatements集合
			Iterator<XMLStatementBuilder> iter = incompleteStatements.iterator();
			while (iter.hasNext()) {
				try {
					//重新解析sql语句
					iter.next().parseStatementNode();
					//移除XMLStatementBuilder对象
					iter.remove();
				} catch (IncompleteElementException e) {
					// Statement is still missing a resource...
				}
			}
		}
	}
	
	//解析cache-ref节点
	private void cacheRefElement(XNode context) {
		if (context != null) {
			//将当前映射配置文件中namespace与被引用的Cache所在的namespace之间的对应关系
			//记录到cacheRefMap集合中
			configuration.addCacheRef(builderAssistant.getCurrentNamespace(), context.getStringAttribute("namespace"));
			//CacheRefResolver对象创建
			CacheRefResolver cacheRefResolver = new CacheRefResolver(builderAssistant,
					context.getStringAttribute("namespace"));
			try {
				//解析Cache引用，该过程主要是设置MapperBuilderAssistant
				//的currentCache和unresolvedCacheRef字段
				cacheRefResolver.resolveCacheRef();
			} catch (IncompleteElementException e) {
				//如果出现异常，则添加到Configuration的incompleteCacheRefs集合中，稍后在做解析
				configuration.addIncompleteCacheRef(cacheRefResolver);
			}
		}
	}
	
	//解析cache节点
	private void cacheElement(XNode context) throws Exception {
		if (context != null) {
			//解析type属性，默认值为PERPETUAL
			String type = context.getStringAttribute("type", "PERPETUAL");
			//查看type属性对应的cache接口实现
			Class<? extends Cache> typeClass = typeAliasRegistry.resolveAlias(type);
			//解析eviction属性，默认值为LRU
			String eviction = context.getStringAttribute("eviction", "LRU");
			Class<? extends Cache> evictionClass = typeAliasRegistry.resolveAlias(eviction);
			//解析flushInterval属性
			Long flushInterval = context.getLongAttribute("flushInterval");
			//解析size属性
			Integer size = context.getIntAttribute("size");
			//解析readOnly属性，默认值为false
			boolean readWrite = !context.getBooleanAttribute("readOnly", false);
			//解析blocking属性，默认值为false
			boolean blocking = context.getBooleanAttribute("blocking", false);
			//获取cache节点下咋子节点，用于初始化二级缓存
			Properties props = context.getChildrenAsProperties();
			//通过MapperBuilderAssistant创建Cache对象，并添加到Configuration.caches集合中保存
			builderAssistant.useNewCache(typeClass, evictionClass, flushInterval, size, readWrite, blocking, props);
		}
	}

	private void parameterMapElement(List<XNode> list) throws Exception {
		for (XNode parameterMapNode : list) {
			String id = parameterMapNode.getStringAttribute("id");
			String type = parameterMapNode.getStringAttribute("type");
			Class<?> parameterClass = resolveClass(type);
			List<XNode> parameterNodes = parameterMapNode.evalNodes("parameter");
			List<ParameterMapping> parameterMappings = new ArrayList<ParameterMapping>();
			for (XNode parameterNode : parameterNodes) {
				String property = parameterNode.getStringAttribute("property");
				String javaType = parameterNode.getStringAttribute("javaType");
				String jdbcType = parameterNode.getStringAttribute("jdbcType");
				String resultMap = parameterNode.getStringAttribute("resultMap");
				String mode = parameterNode.getStringAttribute("mode");
				String typeHandler = parameterNode.getStringAttribute("typeHandler");
				Integer numericScale = parameterNode.getIntAttribute("numericScale");
				ParameterMode modeEnum = resolveParameterMode(mode);
				Class<?> javaTypeClass = resolveClass(javaType);
				JdbcType jdbcTypeEnum = resolveJdbcType(jdbcType);
				@SuppressWarnings("unchecked")
				Class<? extends TypeHandler<?>> typeHandlerClass = (Class<? extends TypeHandler<?>>) resolveClass(
						typeHandler);
				ParameterMapping parameterMapping = builderAssistant.buildParameterMapping(parameterClass, property,
						javaTypeClass, jdbcTypeEnum, resultMap, modeEnum, typeHandlerClass, numericScale);
				parameterMappings.add(parameterMapping);
			}
			builderAssistant.addParameterMap(id, parameterClass, parameterMappings);
		}
	}

	private void resultMapElements(List<XNode> list) throws Exception {
		for (XNode resultMapNode : list) {
			try {
				resultMapElement(resultMapNode);
			} catch (IncompleteElementException e) {
				// ignore, it will be retried
			}
		}
	}

	private ResultMap resultMapElement(XNode resultMapNode) throws Exception {
		return resultMapElement(resultMapNode, Collections.<ResultMapping>emptyList());
	}

	//解析resultMap节点
	private ResultMap resultMapElement(XNode resultMapNode, List<ResultMapping> additionalResultMappings)
			throws Exception {
		ErrorContext.instance().activity("processing " + resultMapNode.getValueBasedIdentifier());
		//获取resultMap的id属性，默认值会拼接所有父节点的id或value或Property属性值
		String id = resultMapNode.getStringAttribute("id", resultMapNode.getValueBasedIdentifier());
		//获取resultMap的type属性，表示把结果集映射成type指定类型的对象，注意其默认值
		String type = resultMapNode.getStringAttribute("type", resultMapNode.getStringAttribute("ofType",
				resultMapNode.getStringAttribute("resultType", resultMapNode.getStringAttribute("javaType"))));
		//获取resultMap的extends属性改属性指定了该resultMap节点的继承关系
		String extend = resultMapNode.getStringAttribute("extends");
		//获取resultMap的autoMapping属性
		//设置为true时，则启动自动映射功能，即自动查找与列名同名的属性名，并调用setter方法
		//设置为false时，则需要在resultMap节点内明确注明映射关系才会调用的setter方法
		Boolean autoMapping = resultMapNode.getBooleanAttribute("autoMapping");
		//解析type类型
		Class<?> typeClass = resolveClass(type);
		Discriminator discriminator = null;
		//该集合主要记录解析的结果
		List<ResultMapping> resultMappings = new ArrayList<ResultMapping>();
		resultMappings.addAll(additionalResultMappings);
		//处理resultMap节点的子节点
		List<XNode> resultChildren = resultMapNode.getChildren();
		for (XNode resultChild : resultChildren) {
			//处理constructor节点 
			if ("constructor".equals(resultChild.getName())) {
				processConstructorElement(resultChild, typeClass, resultMappings);
			//处理discriminator节点 
			} else if ("discriminator".equals(resultChild.getName())) {
				discriminator = processDiscriminatorElement(resultChild, typeClass, resultMappings);
			} else {
				//处理id、result、assocation、collection节点 
				List<ResultFlag> flags = new ArrayList<ResultFlag>();
				//如果是id节点。则向flags集合中添加ResultFlag.ID
				if ("id".equals(resultChild.getName())) {
					flags.add(ResultFlag.ID);
				}
				//创建ResultMapping对象，并添加到resultMapping集合中保存
				resultMappings.add(buildResultMappingFromContext(resultChild, typeClass, flags));
			}
		}
		ResultMapResolver resultMapResolver = new ResultMapResolver(builderAssistant, id, typeClass, extend,
				discriminator, resultMappings, autoMapping);
		try {
			//创建ResultMap对象，并添加到Configuration.resultMaps集合中，该集合是StrictMap类型
			return resultMapResolver.resolve();
		} catch (IncompleteElementException e) {
			configuration.addIncompleteResultMap(resultMapResolver);
			throw e;
		}
	}
	
	//constructor节点解析
	private void processConstructorElement(XNode resultChild, Class<?> resultType, List<ResultMapping> resultMappings)
			throws Exception {
		//获取constructor节点的子节点
		List<XNode> argChildren = resultChild.getChildren();
		for (XNode argChild : argChildren) {
			List<ResultFlag> flags = new ArrayList<ResultFlag>();
			//添加CONSTRUCTOR标志
			flags.add(ResultFlag.CONSTRUCTOR);
			//对于idArg节点，添加ID标志
			if ("idArg".equals(argChild.getName())) {
				flags.add(ResultFlag.ID);
			}
			//创建ResultMapping对象，并添加到resultMappings集合中
			resultMappings.add(buildResultMappingFromContext(argChild, resultType, flags));
		}
	}
	
	//解析discriminator节点
	private Discriminator processDiscriminatorElement(XNode context, Class<?> resultType,
			List<ResultMapping> resultMappings) throws Exception {
		//获取column、javatype、jdbctype、typeHandler属性
		String column = context.getStringAttribute("column");
		String javaType = context.getStringAttribute("javaType");
		String jdbcType = context.getStringAttribute("jdbcType");
		String typeHandler = context.getStringAttribute("typeHandler");
		Class<?> javaTypeClass = resolveClass(javaType);
		@SuppressWarnings("unchecked")
		Class<? extends TypeHandler<?>> typeHandlerClass = (Class<? extends TypeHandler<?>>) resolveClass(typeHandler);
		JdbcType jdbcTypeEnum = resolveJdbcType(jdbcType);
		//处理discriminator节点的子节点
		Map<String, String> discriminatorMap = new HashMap<String, String>();
		for (XNode caseChild : context.getChildren()) {
			String value = caseChild.getStringAttribute("value");
			//调用processNestedResultMappings()方法创建嵌套的ResultMap对象
			String resultMap = caseChild.getStringAttribute("resultMap",
					processNestedResultMappings(caseChild, resultMappings));
			//记录该列值与对应选择的ResultMap的ID
			discriminatorMap.put(value, resultMap);
		}
		//创建Discriminator对象
		return builderAssistant.buildDiscriminator(resultType, column, javaTypeClass, jdbcTypeEnum, typeHandlerClass,
				discriminatorMap);
	}

	private void sqlElement(List<XNode> list) throws Exception {
		if (configuration.getDatabaseId() != null) {
			sqlElement(list, configuration.getDatabaseId());
		}
		sqlElement(list, null);
	}
	
	//解析sql节点
	private void sqlElement(List<XNode> list, String requiredDatabaseId) throws Exception {
		//遍历sql节点
		for (XNode context : list) {
			//获取databaseId属性
			String databaseId = context.getStringAttribute("databaseId");
			//获取id属性
			String id = context.getStringAttribute("id");
			//为id添加命名空间
			id = builderAssistant.applyCurrentNamespace(id, false);
			//检测sql的databaseId与当前的Configuration中记录的databaseId是否一致
			if (databaseIdMatchesCurrent(id, databaseId, requiredDatabaseId)) {
				//记录到sqlFragments中保存
				sqlFragments.put(id, context);
			}
		}
	}

	private boolean databaseIdMatchesCurrent(String id, String databaseId, String requiredDatabaseId) {
		if (requiredDatabaseId != null) {
			if (!requiredDatabaseId.equals(databaseId)) {
				return false;
			}
		} else {
			if (databaseId != null) {
				return false;
			}
			// skip this fragment if there is a previous one with a not null
			// databaseId
			if (this.sqlFragments.containsKey(id)) {
				XNode context = this.sqlFragments.get(id);
				if (context.getStringAttribute("databaseId") != null) {
					return false;
				}
			}
		}
		return true;
	}

	private ResultMapping buildResultMappingFromContext(XNode context, Class<?> resultType, List<ResultFlag> flags)
			throws Exception {
		String property;
		//获取该节点的property属性值
		if (flags.contains(ResultFlag.CONSTRUCTOR)) {
			property = context.getStringAttribute("name");
		} else {
			property = context.getStringAttribute("property");
		}
		String column = context.getStringAttribute("column");
		String javaType = context.getStringAttribute("javaType");
		String jdbcType = context.getStringAttribute("jdbcType");
		String nestedSelect = context.getStringAttribute("select");
		//如果未指定association节点的resultmap属性，则是匿名的嵌套映射，需要通过
		//processNestedResultMappings()方法来解析该匿名的嵌套映射
		String nestedResultMap = context.getStringAttribute("resultMap",
				processNestedResultMappings(context, Collections.<ResultMapping>emptyList()));
		String notNullColumn = context.getStringAttribute("notNullColumn");
		String columnPrefix = context.getStringAttribute("columnPrefix");
		String typeHandler = context.getStringAttribute("typeHandler");
		String resultSet = context.getStringAttribute("resultSet");
		String foreignColumn = context.getStringAttribute("foreignColumn");
		boolean lazy = "lazy".equals(
				context.getStringAttribute("fetchType", configuration.isLazyLoadingEnabled() ? "lazy" : "eager"));
		Class<?> javaTypeClass = resolveClass(javaType);
		@SuppressWarnings("unchecked")
		Class<? extends TypeHandler<?>> typeHandlerClass = (Class<? extends TypeHandler<?>>) resolveClass(typeHandler);
		JdbcType jdbcTypeEnum = resolveJdbcType(jdbcType);
		//创建ResultMap对象
		return builderAssistant.buildResultMapping(resultType, property, column, javaTypeClass, jdbcTypeEnum,
				nestedSelect, nestedResultMap, notNullColumn, columnPrefix, typeHandlerClass, flags, resultSet,
				foreignColumn, lazy);
	}
	
	
	private String processNestedResultMappings(XNode context, List<ResultMapping> resultMappings) throws Exception {
		//只会处理assocation|collection|case三种节点
		if ("association".equals(context.getName()) || "collection".equals(context.getName())
				|| "case".equals(context.getName())) {
			//指定了select属性之后，不会生成嵌套的ResultMap对象
			if (context.getStringAttribute("select") == null) {
				//创建ResultMap对象，并添加到Configuration.resultMaps集合中
				ResultMap resultMap = resultMapElement(context, resultMappings);
				return resultMap.getId();
			}
		}
		return null;
	}
	
	//完成看配置文件与对应的Mapepr接口的绑定
	private void bindMapperForNamespace() {
		String namespace = builderAssistant.getCurrentNamespace();
		if (namespace != null) {
			Class<?> boundType = null;
			try {
				//解析命名空间的对应的类型
				boundType = Resources.classForName(namespace);
			} catch (ClassNotFoundException e) {
				// ignore, bound type is not required
			}
			if (boundType != null) {
				//是否已经加载了boundType接口
				if (!configuration.hasMapper(boundType)) {
					// Spring may not know the real resource name so we set a
					// flag
					// to prevent loading again this resource from the mapper
					// interface
					// look at MapperAnnotationBuilder#loadXmlResource
					//追加namespace前缀，并添加到Configuration.loadResources集合中保存
					configuration.addLoadedResource("namespace:" + namespace);
					//调用MapperRegister.addMapper()方法，注册boundType接口
					configuration.addMapper(boundType);
				}
			}
		}
	}

}

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
package org.apache.ibatis.executor;

import static org.apache.ibatis.executor.ExecutionPlaceholder.EXECUTION_PLACEHOLDER;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.cache.impl.PerpetualCache;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.statement.StatementUtil;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;
import org.apache.ibatis.logging.jdbc.ConnectionLogger;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.mapping.StatementType;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.transaction.Transaction;
import org.apache.ibatis.type.TypeHandlerRegistry;

/**
 * @author Clinton Begin 实现Executor接口的抽象类 主要提供了缓存管理和事务管理
 */
public abstract class BaseExecutor implements Executor {

	private static final Log log = LogFactory.getLog(BaseExecutor.class);

	// 事务对象，实现事务的提交、回滚和关闭操作
	protected Transaction transaction;
	// 其中封装的Executor对象
	protected Executor wrapper;

	// 延迟加载队列
	protected ConcurrentLinkedQueue<DeferredLoad> deferredLoads;
	// 一级缓存,用于缓存Executor对象查询结果集映射得到结果对象
	protected PerpetualCache localCache;
	// 一级缓存，用于缓存输出类型参数
	protected PerpetualCache localOutputParameterCache;
	protected Configuration configuration;

	// 用来记录嵌套查询的层数
	protected int queryStack;
	private boolean closed;

	protected BaseExecutor(Configuration configuration, Transaction transaction) {
		this.transaction = transaction;
		this.deferredLoads = new ConcurrentLinkedQueue<DeferredLoad>();
		this.localCache = new PerpetualCache("LocalCache");
		this.localOutputParameterCache = new PerpetualCache("LocalOutputParameterCache");
		this.closed = false;
		this.configuration = configuration;
		this.wrapper = this;
	}

	@Override
	public Transaction getTransaction() {
		if (closed) {
			throw new ExecutorException("Executor was closed.");
		}
		return transaction;
	}

	@Override
	public void close(boolean forceRollback) {
		try {
			try {
				//忽略缓存中的sql语句
				rollback(forceRollback);
			} finally {
				if (transaction != null) {
					transaction.close();
				}
			}
		} catch (SQLException e) {
			// Ignore. There's nothing that can be done at this point.
			log.warn("Unexpected exception on closing transaction.  Cause: " + e);
		} finally {
			transaction = null;
			deferredLoads = null;
			localCache = null;
			localOutputParameterCache = null;
			closed = true;
		}
	}

	@Override
	public boolean isClosed() {
		return closed;
	}
	
	// 负责执行insert|update|delete语句
	@Override
	public int update(MappedStatement ms, Object parameter) throws SQLException {
		ErrorContext.instance().resource(ms.getResource()).activity("executing an update").object(ms.getId());
		//判断Executor是否关闭
		if (closed) {
			throw new ExecutorException("Executor was closed.");
		}
		//清空缓存，因为执行sql语句后，数据库数据已经更新，可能会出现数据库与缓存数据不一致的情况
		clearLocalCache();
		return doUpdate(ms, parameter);
	}

	@Override
	public List<BatchResult> flushStatements() throws SQLException {
		return flushStatements(false);
	}
	
	//针对批处理多条sql语句
	public List<BatchResult> flushStatements(boolean isRollBack) throws SQLException {
		if (closed) {
			throw new ExecutorException("Executor was closed.");
		}
		//是否执行缓存中的sql语句，false是执行，true是不执行
		return doFlushStatements(isRollBack);
	}
	
	@Override
	public <E> List<E> query(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler)
			throws SQLException {
		//获取BoundSql对象
		BoundSql boundSql = ms.getBoundSql(parameter);
		//创建CacheKey对象
		CacheKey key = createCacheKey(ms, parameter, rowBounds, boundSql);
		//调用query()的重载方法
		return query(ms, parameter, rowBounds, resultHandler, key, boundSql);
	}

	@SuppressWarnings("unchecked")
	@Override
	public <E> List<E> query(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler,
			CacheKey key, BoundSql boundSql) throws SQLException {
		ErrorContext.instance().resource(ms.getResource()).activity("executing a query").object(ms.getId());
		//检测到当前Executor是否已关闭
		if (closed) {
			throw new ExecutorException("Executor was closed.");
		}
		if (queryStack == 0 && ms.isFlushCacheRequired()) {
			//非嵌套查询，并且select节点配置的flushCache属性为true时
			//才会清空一级缓存，flushCache配置项是影响一级缓存中结果对象
			//存活时长的第一个方面
			clearLocalCache();
		}
		List<E> list;
		try {
			//增加查询层数
			queryStack++;
			//查询一级缓存
			list = resultHandler == null ? (List<E>) localCache.getObject(key) : null;
			if (list != null) {
				//针对存储过程调用的处理。其功能是:在一级缓存命中时，获取缓存中保存的输出类型参数，
				//并设置到用户传入的实参对象中
				handleLocallyCachedOutputParameters(ms, key, parameter, boundSql);
			} else {
				//其中会调用doQuery()方法完成数据库查询，并得到映射后的结果对象
				list = queryFromDatabase(ms, parameter, rowBounds, resultHandler, key, boundSql);
			}
		} finally {
			//当前查询完成，查询层次减少
			queryStack--;
		}
		
		//延迟加载
		if (queryStack == 0) { 
			//在最外层的查询结束后，所有的嵌套查询也已经完成，相关的缓存项也已经完全加载
			//所以可以开始加载一级缓存中缓存的嵌套查询的结果对象
			for (DeferredLoad deferredLoad : deferredLoads) {
				deferredLoad.load();
			}
			// issue #601
			//加载完成后，清空deferredLoads集合
			deferredLoads.clear();
			if (configuration.getLocalCacheScope() == LocalCacheScope.STATEMENT) {
				// issue #482
				//根据localCacheScope配置决定是否清空一级缓存, localCacheScope配置是影响一级缓存
				//中结果对象存活时长的第二方面
				clearLocalCache();
			}
		}
		return list;
	}
	
	
	@Override
	public <E> Cursor<E> queryCursor(MappedStatement ms, Object parameter, RowBounds rowBounds) throws SQLException {
		BoundSql boundSql = ms.getBoundSql(parameter);
		return doQueryCursor(ms, parameter, rowBounds, boundSql);
	}

	@Override
	public void deferLoad(MappedStatement ms, MetaObject resultObject, String property, CacheKey key,
			Class<?> targetType) {
		//边界检测
		if (closed) {
			throw new ExecutorException("Executor was closed.");
		}
		//创建DeferredLoad对象
		DeferredLoad deferredLoad = new DeferredLoad(resultObject, property, key, localCache, configuration,
				targetType);
		if (deferredLoad.canLoad()) {
			//一级缓存中已经记录了指定查询的结果对象，直接从缓存中加载对象，并设置到外层对象中
			deferredLoad.load();
		} else {
			//将DeferredLoad对象添加到deferredLoads队列中。待整个外层查询结束后，在加载该结果对象
			deferredLoads.add(new DeferredLoad(resultObject, property, key, localCache, configuration, targetType));
		}
	}

	@Override
	public CacheKey createCacheKey(MappedStatement ms, Object parameterObject, RowBounds rowBounds, BoundSql boundSql) {
		//检测到当前Executor是否已经关闭
		if (closed) {
			throw new ExecutorException("Executor was closed.");
		}
		//创建CacheKey对象
		CacheKey cacheKey = new CacheKey();
		//将MappedStatement的id添加到CacheKey对象中
		cacheKey.update(ms.getId());
		cacheKey.update(rowBounds.getOffset());
		cacheKey.update(rowBounds.getLimit());
		cacheKey.update(boundSql.getSql());
		List<ParameterMapping> parameterMappings = boundSql.getParameterMappings();
		TypeHandlerRegistry typeHandlerRegistry = ms.getConfiguration().getTypeHandlerRegistry();
		
		// mimic DefaultParameterHandler logic
		// 获取用户传入的实参，并添加到CacheKey对象中
		for (ParameterMapping parameterMapping : parameterMappings) {
			// 过滤掉输出类型的参数
			if (parameterMapping.getMode() != ParameterMode.OUT) {
				Object value;
				String propertyName = parameterMapping.getProperty();
				if (boundSql.hasAdditionalParameter(propertyName)) {
					value = boundSql.getAdditionalParameter(propertyName);
				} else if (parameterObject == null) {
					value = null;
				} else if (typeHandlerRegistry.hasTypeHandler(parameterObject.getClass())) {
					value = parameterObject;
				} else {
					MetaObject metaObject = configuration.newMetaObject(parameterObject);
					value = metaObject.getValue(propertyName);
				}
				
				// 将实参添加到CacheKey对象中
				cacheKey.update(value);
			}
		}
		
		//如果Environment的id不为空，则将其添加到CacheKey中
		if (configuration.getEnvironment() != null) {
			// issue #176
			cacheKey.update(configuration.getEnvironment().getId());
		}
		return cacheKey;
	}
	
	//检测缓存中是否缓存CacheKey对应的对象
	@Override
	public boolean isCached(MappedStatement ms, CacheKey key) {
		return localCache.getObject(key) != null;
	}

	@Override
	public void commit(boolean required) throws SQLException {
		if (closed) {
			throw new ExecutorException("Cannot commit, transaction is already closed");
		}
		clearLocalCache();
		flushStatements();
		//根据required参数确定是否提交事务
		if (required) {
			transaction.commit();
		}
	}

	@Override
	public void rollback(boolean required) throws SQLException {
		if (!closed) {
			try {
				clearLocalCache();
				flushStatements(true);
			} finally {
				if (required) {
					transaction.rollback();
				}
			}
		}
	}

	@Override
	public void clearLocalCache() {
		//这是影响一级缓存中数据存活时长的第三个方面
		if (!closed) {
			localCache.clear();
			localOutputParameterCache.clear();
		}
	}

	protected abstract int doUpdate(MappedStatement ms, Object parameter) throws SQLException;

	protected abstract List<BatchResult> doFlushStatements(boolean isRollback) throws SQLException;

	protected abstract <E> List<E> doQuery(MappedStatement ms, Object parameter, RowBounds rowBounds,
			ResultHandler resultHandler, BoundSql boundSql) throws SQLException;

	protected abstract <E> Cursor<E> doQueryCursor(MappedStatement ms, Object parameter, RowBounds rowBounds,
			BoundSql boundSql) throws SQLException;

	protected void closeStatement(Statement statement) {
		if (statement != null) {
			try {
				statement.close();
			} catch (SQLException e) {
				// ignore
			}
		}
	}

	/**
	 * Apply a transaction timeout.
	 * 
	 * @param statement
	 *            a current statement
	 * @throws SQLException
	 *             if a database access error occurs, this method is called on a
	 *             closed <code>Statement</code>
	 * @since 3.4.0
	 * @see StatementUtil#applyTransactionTimeout(Statement, Integer, Integer)
	 */
	protected void applyTransactionTimeout(Statement statement) throws SQLException {
		StatementUtil.applyTransactionTimeout(statement, statement.getQueryTimeout(), transaction.getTimeout());
	}

	private void handleLocallyCachedOutputParameters(MappedStatement ms, CacheKey key, Object parameter,
			BoundSql boundSql) {
		if (ms.getStatementType() == StatementType.CALLABLE) {
			final Object cachedParameter = localOutputParameterCache.getObject(key);
			if (cachedParameter != null && parameter != null) {
				final MetaObject metaCachedParameter = configuration.newMetaObject(cachedParameter);
				final MetaObject metaParameter = configuration.newMetaObject(parameter);
				for (ParameterMapping parameterMapping : boundSql.getParameterMappings()) {
					if (parameterMapping.getMode() != ParameterMode.IN) {
						final String parameterName = parameterMapping.getProperty();
						final Object cachedValue = metaCachedParameter.getValue(parameterName);
						metaParameter.setValue(parameterName, cachedValue);
					}
				}
			}
		}
	}
	
	private <E> List<E> queryFromDatabase(MappedStatement ms, Object parameter, RowBounds rowBounds,
			ResultHandler resultHandler, CacheKey key, BoundSql boundSql) throws SQLException {
		List<E> list;
		//在缓存中添加占位符
		localCache.putObject(key, EXECUTION_PLACEHOLDER);
		try {
			//调用doQuery()方法(抽象方法),完成数据库查询操作,并返回结果对象
			list = doQuery(ms, parameter, rowBounds, resultHandler, boundSql);
		} finally {
			//删除占位符
			localCache.removeObject(key);
		}
		//将真正的结果对象添加到一级缓存中
		localCache.putObject(key, list);
		//是否为存储过程调用
		if (ms.getStatementType() == StatementType.CALLABLE) {
			//缓存输出类型的参数
			localOutputParameterCache.putObject(key, parameter);
		}
		return list;
	}

	protected Connection getConnection(Log statementLog) throws SQLException {
		Connection connection = transaction.getConnection();
		if (statementLog.isDebugEnabled()) {
			return ConnectionLogger.newInstance(connection, statementLog, queryStack);
		} else {
			return connection;
		}
	}

	@Override
	public void setExecutorWrapper(Executor wrapper) {
		this.wrapper = wrapper;
	}

	private static class DeferredLoad {
		
		//外层对象对应的MetaObject对象
		private final MetaObject resultObject;
		//延迟加载的属性名称
		private final String property;
		//延迟加载的属性的类型
		private final Class<?> targetType;
		//延迟加载的对象在一级缓存中相应的CacheKey的对象
		private final CacheKey key;
		//一级缓存，与BaseExecutor.loadCache字段指向同一PerpetualCache对象
		private final PerpetualCache localCache;
		private final ObjectFactory objectFactory;
		//ResultExecutor负责结果对象的类型转换
		private final ResultExtractor resultExtractor;

		// issue #781
		public DeferredLoad(MetaObject resultObject, String property, CacheKey key, PerpetualCache localCache,
				Configuration configuration, Class<?> targetType) {
			this.resultObject = resultObject;
			this.property = property;
			this.key = key;
			this.localCache = localCache;
			this.objectFactory = configuration.getObjectFactory();
			this.resultExtractor = new ResultExtractor(configuration, objectFactory);
			this.targetType = targetType;
		}
		
		//负责监测缓存项是否已经完全加载到缓存中
		public boolean canLoad() {
			//检测缓存是否指定的结果对象以及检测是否为占位符
			return localCache.getObject(key) != null && localCache.getObject(key) != EXECUTION_PLACEHOLDER;
		}
		
		public void load() {
			@SuppressWarnings("unchecked")
			// we suppose we get back a List
			// 从缓存中查询指定的结果对象
			List<Object> list = (List<Object>) localCache.getObject(key);
			//将缓存的结果对象转换为指定类型
			Object value = resultExtractor.extractObjectFromList(list, targetType);
			//设置到外层对象的对应属性
			resultObject.setValue(property, value);
		}

	}

}

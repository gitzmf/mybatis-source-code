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

import java.sql.SQLException;
import java.util.List;

import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.cache.TransactionalCacheManager;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.mapping.StatementType;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.transaction.Transaction;

/**
 * @author Clinton Begin
 * @author Eduardo Macarron
 * 二级缓存
 */
public class CachingExecutor implements Executor {

	private final Executor delegate;
	private final TransactionalCacheManager tcm = new TransactionalCacheManager();

	public CachingExecutor(Executor delegate) {
		this.delegate = delegate;
		delegate.setExecutorWrapper(this);
	}

	@Override
	public Transaction getTransaction() {
		return delegate.getTransaction();
	}

	@Override
	public void close(boolean forceRollback) {
		try {
			// issues #499, #524 and #573
			if (forceRollback) {
				tcm.rollback();
			} else {
				tcm.commit();
			}
		} finally {
			delegate.close(forceRollback);
		}
	}

	@Override
	public boolean isClosed() {
		return delegate.isClosed();
	}

	@Override
	public int update(MappedStatement ms, Object parameterObject) throws SQLException {
		flushCacheIfRequired(ms);
		return delegate.update(ms, parameterObject);
	}

	@Override
	public <E> List<E> query(MappedStatement ms, Object parameterObject, RowBounds rowBounds,
			ResultHandler resultHandler) throws SQLException {
		//获取boundSql对象
		BoundSql boundSql = ms.getBoundSql(parameterObject);
		//创建CacheKey对象
		CacheKey key = createCacheKey(ms, parameterObject, rowBounds, boundSql);
		return query(ms, parameterObject, rowBounds, resultHandler, key, boundSql);
	}

	@Override
	public <E> Cursor<E> queryCursor(MappedStatement ms, Object parameter, RowBounds rowBounds) throws SQLException {
		flushCacheIfRequired(ms);
		return delegate.queryCursor(ms, parameter, rowBounds);
	}
	
	@Override
	public <E> List<E> query(MappedStatement ms, Object parameterObject, RowBounds rowBounds,
			ResultHandler resultHandler, CacheKey key, BoundSql boundSql) throws SQLException {
		//获取查询语句所在的命名空间对应的二级缓存
		Cache cache = ms.getCache();
		//是否开启了二级缓存功能
		if (cache != null) {
			//根据select节点配置是否清空二级缓存
			flushCacheIfRequired(ms);
			//检测sql节点的useCache配置以及是否使用了resultHandler配置
			if (ms.isUseCache() && resultHandler == null) {
				//二级缓存不能保存输出类型参数，如果查询操作调用了包含输出类型参数的存储过程
				//则报错
				ensureNoOutParams(ms, parameterObject, boundSql);
				//查询二级缓存
				@SuppressWarnings("unchecked")
				List<E> list = (List<E>) tcm.getObject(cache, key);
				//二级缓存没有相对应的结果对象，调用封装的Execuotr对象的query方法
				if (list == null) {
					list = delegate.<E>query(ms, parameterObject, rowBounds, resultHandler, key, boundSql);
					tcm.putObject(cache, key, list); // issue #578 and #116
				}
				return list;
			}
		}
		//没有开启二级缓存功能
		return delegate.<E>query(ms, parameterObject, rowBounds, resultHandler, key, boundSql);
	}

	@Override
	public List<BatchResult> flushStatements() throws SQLException {
		return delegate.flushStatements();
	}

	@Override
	public void commit(boolean required) throws SQLException {
		delegate.commit(required);
		tcm.commit();
	}

	@Override
	public void rollback(boolean required) throws SQLException {
		try {
			delegate.rollback(required);
		} finally {
			if (required) {
				tcm.rollback();
			}
		}
	}

	private void ensureNoOutParams(MappedStatement ms, Object parameter, BoundSql boundSql) {
		if (ms.getStatementType() == StatementType.CALLABLE) {
			for (ParameterMapping parameterMapping : boundSql.getParameterMappings()) {
				if (parameterMapping.getMode() != ParameterMode.IN) {
					throw new ExecutorException(
							"Caching stored procedures with OUT params is not supported.  Please configure useCache=false in "
									+ ms.getId() + " statement.");
				}
			}
		}
	}

	@Override
	public CacheKey createCacheKey(MappedStatement ms, Object parameterObject, RowBounds rowBounds, BoundSql boundSql) {
		return delegate.createCacheKey(ms, parameterObject, rowBounds, boundSql);
	}

	@Override
	public boolean isCached(MappedStatement ms, CacheKey key) {
		return delegate.isCached(ms, key);
	}

	@Override
	public void deferLoad(MappedStatement ms, MetaObject resultObject, String property, CacheKey key,
			Class<?> targetType) {
		delegate.deferLoad(ms, resultObject, property, key, targetType);
	}

	@Override
	public void clearLocalCache() {
		delegate.clearLocalCache();
	}

	private void flushCacheIfRequired(MappedStatement ms) {
		Cache cache = ms.getCache();
		if (cache != null && ms.isFlushCacheRequired()) {
			tcm.clear(cache);
		}
	}

	@Override
	public void setExecutorWrapper(Executor executor) {
		throw new UnsupportedOperationException("This method should not be called");
	}

}

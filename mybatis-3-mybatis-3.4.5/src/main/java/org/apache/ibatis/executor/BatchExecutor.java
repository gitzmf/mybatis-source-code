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

import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.keygen.Jdbc3KeyGenerator;
import org.apache.ibatis.executor.keygen.KeyGenerator;
import org.apache.ibatis.executor.keygen.NoKeyGenerator;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.transaction.Transaction;

/**
 * @author Jeff Butler
 * 实现了批处理多条SQL语句的功能
 */
public class BatchExecutor extends BaseExecutor {

	public static final int BATCH_UPDATE_RETURN_VALUE = Integer.MIN_VALUE + 1002;
	
	//缓存多个Statement对象，其中每个Statement对象都还存了多条sql语句
	private final List<Statement> statementList = new ArrayList<Statement>();
	//记录了批处理的结果，BatchResult通过updateCounts字段记录每个Statement执行批处理的结果
	private final List<BatchResult> batchResultList = new ArrayList<BatchResult>();
	//当前执行的sql语句
	private String currentSql;
	//记录当前执行的MappedStatement对象
	private MappedStatement currentStatement;

	public BatchExecutor(Configuration configuration, Transaction transaction) {
		super(configuration, transaction);
	}

	@Override
	public int doUpdate(MappedStatement ms, Object parameterObject) throws SQLException {
		final Configuration configuration = ms.getConfiguration();
		final StatementHandler handler = configuration.newStatementHandler(this, ms, parameterObject, RowBounds.DEFAULT,
				null, null);
		final BoundSql boundSql = handler.getBoundSql();
		final String sql = boundSql.getSql();
		final Statement stmt;
		//如果当前执行的sql模式与上次执行的SQL模式相同且对应的MappedStatement对象相同
		if (sql.equals(currentSql) && ms.equals(currentStatement)) {
			//获取集合中最后一个Statement对象
			int last = statementList.size() - 1;
			stmt = statementList.get(last);
			applyTransactionTimeout(stmt);
			//绑定实参，处理占位符
			handler.parameterize(stmt);// fix Issues 322
			BatchResult batchResult = batchResultList.get(last);
			//记录用户传入的实参
			batchResult.addParameterObject(parameterObject);
		} else {
			Connection connection = getConnection(ms.getStatementLog());
			stmt = handler.prepare(connection, transaction.getTimeout());
			handler.parameterize(stmt); // fix Issues 322
			currentSql = sql;
			currentStatement = ms;
			statementList.add(stmt);
			batchResultList.add(new BatchResult(ms, sql, parameterObject));
		}
		// handler.parameterize(stmt);
		//底层通过Statement.addBatch()方法添加sql语句
		handler.batch(stmt);
		return BATCH_UPDATE_RETURN_VALUE;
	}

	@Override
	public <E> List<E> doQuery(MappedStatement ms, Object parameterObject, RowBounds rowBounds,
			ResultHandler resultHandler, BoundSql boundSql) throws SQLException {
		Statement stmt = null;
		try {
			flushStatements();
			Configuration configuration = ms.getConfiguration();
			StatementHandler handler = configuration.newStatementHandler(wrapper, ms, parameterObject, rowBounds,
					resultHandler, boundSql);
			Connection connection = getConnection(ms.getStatementLog());
			stmt = handler.prepare(connection, transaction.getTimeout());
			handler.parameterize(stmt);
			return handler.<E>query(stmt, resultHandler);
		} finally {
			closeStatement(stmt);
		}
	}

	@Override
	protected <E> Cursor<E> doQueryCursor(MappedStatement ms, Object parameter, RowBounds rowBounds, BoundSql boundSql)
			throws SQLException {
		flushStatements();
		Configuration configuration = ms.getConfiguration();
		StatementHandler handler = configuration.newStatementHandler(wrapper, ms, parameter, rowBounds, null, boundSql);
		Connection connection = getConnection(ms.getStatementLog());
		Statement stmt = handler.prepare(connection, transaction.getTimeout());
		handler.parameterize(stmt);
		return handler.<E>queryCursor(stmt);
	}

	@Override
	public List<BatchResult> doFlushStatements(boolean isRollback) throws SQLException {
		try {
			//用于储存批处理的结果
			List<BatchResult> results = new ArrayList<BatchResult>();
			//如果明确指定了要回滚事务，则直接返回空集合，忽略掉statementList集合中记录的sql语句
			if (isRollback) {
				return Collections.emptyList();
			}
			//遍历statementList集合
			for (int i = 0, n = statementList.size(); i < n; i++) {
				//获取Statement对象
				Statement stmt = statementList.get(i);
				applyTransactionTimeout(stmt);
				//获取对应的BatchResult对象
				BatchResult batchResult = batchResultList.get(i);
				try {
					//调用Statement.executeBatch()方法批量执行其中记录的sql语句
					//并使用返回的int数组更新updatecounts字段
					batchResult.setUpdateCounts(stmt.executeBatch());
					MappedStatement ms = batchResult.getMappedStatement();
					List<Object> parameterObjects = batchResult.getParameterObjects();
					//获取配置的KeyGenerator对象
					KeyGenerator keyGenerator = ms.getKeyGenerator();
					if (Jdbc3KeyGenerator.class.equals(keyGenerator.getClass())) {
						Jdbc3KeyGenerator jdbc3KeyGenerator = (Jdbc3KeyGenerator) keyGenerator;
						//获取数据库生成的主键，并配置到parameterObjects中
						jdbc3KeyGenerator.processBatch(ms, stmt, parameterObjects);
					} else if (!NoKeyGenerator.class.equals(keyGenerator.getClass())) { // issue
																						// #141
						for (Object parameter : parameterObjects) {
							keyGenerator.processAfter(this, ms, stmt, parameter);
						}
					}
				} catch (BatchUpdateException e) {
					StringBuilder message = new StringBuilder();
					message.append(batchResult.getMappedStatement().getId()).append(" (batch index #").append(i + 1)
							.append(")").append(" failed.");
					if (i > 0) {
						message.append(" ").append(i)
								.append(" prior sub executor(s) completed successfully, but will be rolled back.");
					}
					throw new BatchExecutorException(message.toString(), e, results, batchResult);
				}
				results.add(batchResult);
			}
			return results;
		} finally {
			for (Statement stmt : statementList) {
				closeStatement(stmt);
			}
			currentSql = null;
			statementList.clear();
			batchResultList.clear();
		}
	}

}

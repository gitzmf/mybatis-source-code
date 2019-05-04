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
package org.apache.ibatis.cache.decorators;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReadWriteLock;

import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;

/**
 * The 2nd level cache transactional buffer.
 * 
 * This class holds all cache entries that are to be added to the 2nd level
 * cache during a Session. Entries are sent to the cache when commit is called
 * or discarded if the Session is rolled back. Blocking cache support has been
 * added. Therefore any get() that returns a cache miss will be followed by a
 * put() so any lock associated with the key can be released.
 * 
 * @author Clinton Begin
 * @author Eduardo Macarron
 * 保存某个SqlSession的某个事务中需要向某个二级缓存中添加的缓存数据
 */
public class TransactionalCache implements Cache {

	private static final Log log = LogFactory.getLog(TransactionalCache.class);
	
	//底层封装的是二级缓存所对应的cache对象
	private final Cache delegate;
	//为true时，表示的是当前TransactionalCache不可查，且当事务提交的时候，清空底层的Cache
	private boolean clearOnCommit;
	//暂时记录添加到TransactionalCache中的数据
	private final Map<Object, Object> entriesToAddOnCommit;
	//记录缓存未命中的CacheKey对象
	private final Set<Object> entriesMissedInCache;

	public TransactionalCache(Cache delegate) {
		this.delegate = delegate;
		this.clearOnCommit = false;
		this.entriesToAddOnCommit = new HashMap<Object, Object>();
		this.entriesMissedInCache = new HashSet<Object>();
	}

	@Override
	public String getId() {
		return delegate.getId();
	}

	@Override
	public int getSize() {
		return delegate.getSize();
	}

	@Override
	public Object getObject(Object key) {
		// issue #116
		//查询底层的Cache是否包含指定的key
		Object object = delegate.getObject(key);
		//不包含，则记录到未命中集合中
		if (object == null) {
			entriesMissedInCache.add(key);
		}
		// issue #146
		if (clearOnCommit) {
			return null;
		} else {
			return object;
		}
	}

	@Override
	public ReadWriteLock getReadWriteLock() {
		return null;
	}
	
	//将缓存项暂存在entriesToAddOnCommit集合中
	@Override
	public void putObject(Object key, Object object) {
		entriesToAddOnCommit.put(key, object);
	}

	@Override
	public Object removeObject(Object key) {
		return null;
	}

	@Override
	public void clear() {
		clearOnCommit = true;
		entriesToAddOnCommit.clear();
	}

	public void commit() {
		if (clearOnCommit) {
			delegate.clear();
		}
		flushPendingEntries();
		reset();
	}

	public void rollback() {
		unlockMissedEntries();
		reset();
	}

	private void reset() {
		clearOnCommit = false;
		entriesToAddOnCommit.clear();
		entriesMissedInCache.clear();
	}

	private void flushPendingEntries() {
		for (Map.Entry<Object, Object> entry : entriesToAddOnCommit.entrySet()) {
			delegate.putObject(entry.getKey(), entry.getValue());
		}
		for (Object entry : entriesMissedInCache) {
			if (!entriesToAddOnCommit.containsKey(entry)) {
				delegate.putObject(entry, null);
			}
		}
	}

	private void unlockMissedEntries() {
		for (Object entry : entriesMissedInCache) {
			try {
				delegate.removeObject(entry);
			} catch (Exception e) {
				log.warn("Unexpected exception while notifiying a rollback to the cache adapter."
						+ "Consider upgrading your cache adapter to the latest version.  Cause: " + e);
			}
		}
	}

}

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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;

import org.apache.ibatis.cache.Cache;

/**
 * Lru (least recently used) cache decorator
 *
 * @author Clinton Begin 近期最少使用算法进行缓存清理的装饰器类
 */
public class LruCache implements Cache {
	// 被装饰的底层cache对象
	private final Cache delegate;
	// key的近期使用情况
	private Map<Object, Object> keyMap;
	// 记录最少使用的缓存项的key
	private Object eldestKey;

	public LruCache(Cache delegate) {
		this.delegate = delegate;
		// 默认设置缓存大小
		setSize(1024);
	}

	@Override
	public String getId() {
		return delegate.getId();
	}

	@Override
	public int getSize() {
		return delegate.getSize();
	}
	
	//重设缓存大小的时候，会重置keyMap字段
	public void setSize(final int size) {
		keyMap = new LinkedHashMap<Object, Object>(size, .75F, true) {
			private static final long serialVersionUID = 4267176411845948333L;
			
			//每次调用put方法的时候，会调用此方法
			@Override
			protected boolean removeEldestEntry(Map.Entry<Object, Object> eldest) {
				boolean tooBig = size() > size;
				// 如果达到缓存上限，则更新eldestKey字段
				if (tooBig) {
					eldestKey = eldest.getKey();
				}
				return tooBig;
			}
		};
	}

	@Override
	public void putObject(Object key, Object value) {
		delegate.putObject(key, value);
		//删除最久未使用的缓存项
		cycleKeyList(key);
	}

	@Override
	public Object getObject(Object key) {
		//修改LinkedHashMap中的记录顺序
		keyMap.get(key); // touch
		return delegate.getObject(key);
	}

	@Override
	public Object removeObject(Object key) {
		return delegate.removeObject(key);
	}

	@Override
	public void clear() {
		delegate.clear();
		keyMap.clear();
	}

	@Override
	public ReadWriteLock getReadWriteLock() {
		return null;
	}

	private void cycleKeyList(Object key) {
		keyMap.put(key, key);
		if (eldestKey != null) {
			delegate.removeObject(eldestKey);
			eldestKey = null;
		}
	}

}

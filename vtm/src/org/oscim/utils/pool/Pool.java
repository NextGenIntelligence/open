/*
 * Copyright 2013 Hannes Janetzek
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along with
 * this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.oscim.utils.pool;

import javax.annotation.CheckReturnValue;

public abstract class Pool<T extends Inlist<T>> {

	protected T pool;

	/**
	 * @param item release resources
	 * @return whether item should be added to
	 *         pool. use to manage pool size manually
	 */
	protected boolean clearItem(T item) {
		return true;
	}

	/**
	 * Release 'item' to pool.
	 * <p>
	 * Usage item = pool.release(item), to ensure to not keep a reference to
	 * item!
	 */
	@CheckReturnValue
	public T release(T item) {
		if (item == null)
			return null;

		if (!clearItem(item))
			return null;

		item.next = pool;
		pool = item;

		return null;
	}

	/**
	 * Release 'list' to pool.
	 * <p>
	 * Usage list = pool.releaseAll(list), to ensure to not keep a reference to
	 * list!
	 */
	@CheckReturnValue
	public T releaseAll(T list) {
		if (list == null)
			return null;

		while (list != null) {
			T next = list.next;

			clearItem(list);

			list.next = pool;
			pool = list;

			list = next;
		}
		return null;
	}

	// remove 'item' from 'list' and add back to pool
	public T release(T list, T item) {
		if (item == null)
			return list;

		clearItem(item);

		if (item == list) {
			T ret = item.next;

			item.next = pool;
			pool = item;

			return ret;
		}

		for (T prev = list, it = list.next; it != null; it = it.next) {

			if (it == item) {
				prev.next = it.next;

				item.next = pool;
				pool = item;
				break;
			}
			prev = it;
		}

		return list;
	}

	protected abstract T createItem();

	public T get() {

		if (pool == null)
			return createItem();

		T ret = pool;
		pool = pool.next;

		ret.next = null;
		return ret;
	}
}

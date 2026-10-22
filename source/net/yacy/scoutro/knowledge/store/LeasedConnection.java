/*
 *  LeasedConnection
 *  Copyright 2026 by Scoutro contributors
 *  Scoutro is an independent community project based on YaCy.
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU General Public License
 *  as published by the Free Software Foundation; either version 2
 *  of the License, or (at your option) any later version.
 *
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  General Public License for more details.
 */

package net.yacy.scoutro.knowledge.store;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The connection a read lease hands to its work.
 * <p>
 * {@code sqlite3_interrupt} only stops statements that are running when it is
 * called; an interrupt between two statements is lost and the next statement
 * runs unbounded. This wrapper therefore checks the lease before every
 * statement execution and every {@code ResultSet.next()}: once the lease is
 * over, no further statement starts. It also tracks the statements of the
 * lease, so the store can close them all before it ends the transaction (an
 * open statement would let a pending interrupt hit the COMMIT), and it refuses
 * transaction control, which belongs to the lease.
 * <p>
 * A statement prepared with SQL alone is kept for the lease once its user closes it, and the next preparation of the same
 * SQL takes it again (0.11.8): a projection that asks the same small question for thousands of rows spent most of its
 * time preparing it again each time. A statement is only reused while no one holds it; all are closed with the lease.
 * <p>
 * It bounds lease misuse by Scoutro's own code; it is not a sandbox.
 */
final class LeasedConnection {

    /** Throws {@link SQLException} with SQLITE_INTERRUPT once the lease is over. */
    interface Check {
        void check() throws SQLException;
    }

    private static final Set<String> CONNECTION_FORBIDDEN = new HashSet<>(List.of("setAutoCommit", "commit",
            "rollback", "setSavepoint", "releaseSavepoint", "close", "abort", "setReadOnly", "setTransactionIsolation",
            "prepareCall", "setCatalog", "setSchema", "setNetworkTimeout"));
    private static final Set<String> STATEMENT_EXECUTE = new HashSet<>(List.of("execute", "executeQuery",
            "executeUpdate", "executeLargeUpdate", "executeBatch", "executeLargeBatch"));

    private final Connection real;
    private final Check check;
    private final Set<Statement> open = Collections.newSetFromMap(new IdentityHashMap<>());
    /** Prepared statements their users closed, by SQL; still open, ready for the next preparation of the same SQL. */
    private final Map<String, ArrayDeque<PreparedStatement>> idle = new HashMap<>();
    /** At most this many idle statements are kept; beyond, a closed one is really closed (SQL built with its values). */
    static final int MAX_IDLE = 256;
    private int idleCount;
    final Connection proxy;

    LeasedConnection(final Connection real, final Check check) {
        this.real = real;
        this.check = check;
        this.proxy = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class}, this::onConnection);
    }

    /** Closes every statement the work left open; afterwards no statement of the lease is active. */
    void closeStatements() {
        final List<Statement> all = new ArrayList<>(this.open);
        this.open.clear();
        this.idle.clear();
        this.idleCount = 0;
        for (final Statement s : all) {
            try {
                s.close();
            } catch (final SQLException | RuntimeException e) {
                // closing anyway
            }
        }
    }

    int openStatements() {
        return this.open.size();
    }

    private Object onConnection(final Object self, final Method m, final Object[] args) throws Throwable {
        final String name = m.getName();
        if (m.getDeclaringClass() == Object.class) {
            return objectMethod(self, m, args);
        }
        if (CONNECTION_FORBIDDEN.contains(name)) {
            throw new SQLException(name + " is not allowed inside a knowledge graph read lease");
        }
        if ("unwrap".equals(name)) {
            if (((Class<?>) args[0]).isInstance(self)) {
                return self;
            }
            throw new SQLException("a knowledge graph read lease does not expose its connection");
        }
        if ("isWrapperFor".equals(name)) {
            return ((Class<?>) args[0]).isInstance(self);
        }
        if ("prepareStatement".equals(name) && args != null && args.length == 1 && args[0] instanceof String) {
            this.check.check();
            final String sql = (String) args[0];
            final ArrayDeque<PreparedStatement> ready = this.idle.get(sql);
            PreparedStatement s = ready == null ? null : ready.pollFirst();
            if (s != null) {
                this.idleCount--;
            } else {
                s = this.real.prepareStatement(sql);
                this.open.add(s);
            }
            return pooled(s, sql);
        }
        if ("createStatement".equals(name) || "prepareStatement".equals(name)) {
            this.check.check();
            final Statement s = (Statement) invoke(this.real, m, args);
            this.open.add(s);
            return statement(s, m.getReturnType());
        }
        return invoke(this.real, m, args);
    }

    private Object statement(final Statement real, final Class<?> type) {
        final Class<?> iface = PreparedStatement.class.isAssignableFrom(type) ? PreparedStatement.class : Statement.class;
        return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {iface}, (p, m, args) -> {
            final String name = m.getName();
            if (m.getDeclaringClass() == Object.class) {
                return objectMethod(p, m, args);
            }
            if ("getConnection".equals(name)) {
                return this.proxy;
            }
            if ("close".equals(name)) {
                this.open.remove(real);
                return invoke(real, m, args);
            }
            if ("unwrap".equals(name) || "isWrapperFor".equals(name)) {
                final boolean own = ((Class<?>) args[0]).isInstance(p);
                if ("isWrapperFor".equals(name)) {
                    return own;
                }
                if (own) {
                    return p;
                }
                throw new SQLException("a knowledge graph read lease does not expose its statements");
            }
            if (STATEMENT_EXECUTE.contains(name)) {
                this.check.check();
            }
            final Object result = invoke(real, m, args);
            return result instanceof ResultSet ? resultSet((ResultSet) result, p) : result;
        });
    }

    /**
     * A prepared statement of the pool, held by its user until close: then its result set is closed, its parameters are
     * cleared and it waits for the next preparation of the same SQL. Statements of a pool are closed with the lease.
     */
    private Object pooled(final PreparedStatement real, final String sql) {
        final boolean[] closed = {false};
        final ResultSet[] current = {null};
        return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {PreparedStatement.class}, (p, m, args) -> {
            final String name = m.getName();
            if (m.getDeclaringClass() == Object.class) {
                return objectMethod(p, m, args);
            }
            if ("close".equals(name)) {
                if (closed[0]) return null;
                closed[0] = true;
                try {
                    if (current[0] != null) current[0].close();
                    if (this.idleCount >= MAX_IDLE) {
                        this.open.remove(real);
                        real.close();
                        return null;
                    }
                    real.clearParameters();
                    this.idle.computeIfAbsent(sql, k -> new ArrayDeque<>()).addLast(real);
                    this.idleCount++;
                } catch (final SQLException e) {
                    this.open.remove(real);
                    real.close();
                }
                return null;
            }
            if ("isClosed".equals(name)) {
                return closed[0];
            }
            if (closed[0]) {
                throw new SQLException("statement is closed");
            }
            if ("getConnection".equals(name)) {
                return this.proxy;
            }
            if ("unwrap".equals(name) || "isWrapperFor".equals(name)) {
                final boolean own = ((Class<?>) args[0]).isInstance(p);
                if ("isWrapperFor".equals(name)) {
                    return own;
                }
                if (own) {
                    return p;
                }
                throw new SQLException("a knowledge graph read lease does not expose its statements");
            }
            if (STATEMENT_EXECUTE.contains(name)) {
                this.check.check();
            }
            final Object result = invoke(real, m, args);
            if (result instanceof ResultSet) {
                current[0] = (ResultSet) result;
                return resultSet((ResultSet) result, p);
            }
            return result;
        });
    }

    private Object resultSet(final ResultSet real, final Object statement) {
        return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {ResultSet.class}, (p, m, args) -> {
            final String name = m.getName();
            if (m.getDeclaringClass() == Object.class) {
                return objectMethod(p, m, args);
            }
            if ("getStatement".equals(name)) {
                return statement;
            }
            if ("unwrap".equals(name) || "isWrapperFor".equals(name)) {
                final boolean own = ((Class<?>) args[0]).isInstance(p);
                if ("isWrapperFor".equals(name)) {
                    return own;
                }
                if (own) {
                    return p;
                }
                throw new SQLException("a knowledge graph read lease does not expose its result sets");
            }
            if ("next".equals(name)) {
                this.check.check();
            }
            return invoke(real, m, args);
        });
    }

    private static Object objectMethod(final Object self, final Method m, final Object[] args) {
        switch (m.getName()) {
            case "equals":
                return self == args[0];
            case "hashCode":
                return System.identityHashCode(self);
            default:
                return "KnowledgeGraphLease@" + Integer.toHexString(System.identityHashCode(self));
        }
    }

    private static Object invoke(final Object target, final Method m, final Object[] args) throws Throwable {
        try {
            return m.invoke(target, args);
        } catch (final InvocationTargetException e) {
            throw e.getCause();
        }
    }
}

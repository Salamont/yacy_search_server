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
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
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

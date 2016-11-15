package org.davidmoten.rx.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.github.davidmoten.guavamini.Lists;
import com.github.davidmoten.guavamini.Preconditions;

import io.reactivex.Flowable;
import io.reactivex.Notification;

public class SelectBuilder {

    private final String sql;
    private final SqlInfo sqlInfo;
    private final Flowable<Connection> connections;

    // mutable
    private List<Object> list = null;
    private Flowable<List<Object>> parameters = null;

    public SelectBuilder(String sql, Flowable<Connection> connections) {
        this.sql = sql;
        this.connections = connections;
        this.sqlInfo = SqlInfo.parse(sql);
    }

    public SelectBuilder parameters(Flowable<List<Object>> parameters) {
        Preconditions.checkArgument(list == null);
        if (this.parameters == null)
            this.parameters = parameters;
        else
            this.parameters = this.parameters.concatWith(parameters);
        return this;
    }

    public SelectBuilder parameterList(List<Object> values) {
        Preconditions.checkArgument(list == null);
        if (this.parameters == null)
            this.parameters = Flowable.just(values);
        else
            this.parameters = this.parameters.concatWith(Flowable.just(values));
        return this;
    }

    public SelectBuilder parameterList(Object... values) {
        Preconditions.checkArgument(list == null);
        if (this.parameters == null)
            this.parameters = Flowable.just(Lists.newArrayList(values));
        else
            this.parameters = this.parameters.concatWith(Flowable.just(Lists.newArrayList(values)));
        return this;
    }

    public SelectBuilder parameter(String name, Object value) {
        Preconditions.checkArgument(parameters == null);
        if (list == null) {
            list = new ArrayList<>();
        }
        this.list.add(new Parameter(name, value));
        return this;
    }

    public SelectBuilder parameters(Object... values) {
        if (values.length == 0) {
            // no effect
            return this;
        }
        Preconditions.checkArgument(list == null);
        Preconditions.checkArgument(sqlInfo.numParameters() > 0, "no parameters present in sql!");
        Preconditions.checkArgument(values.length % sqlInfo.numParameters() == 0,
                "number of values should be a multiple of number of parameters in sql: " + sql);
        Preconditions.checkArgument(Arrays.stream(values).allMatch(o -> sqlInfo.names().isEmpty()
                || (o instanceof Parameter && ((Parameter) o).hasName())));
        return parameters(Flowable.fromArray(values).buffer(sqlInfo.numParameters()));
    }

    public <T> Flowable<T> getAs(Class<T> cls) {
        resolveParameters();
        return Select.create(connections.firstOrError(), parameters, sql,
                rs -> Util.mapObject(rs, cls, 1));
    }

    private void resolveParameters() {
        if (list != null) {
            parameters = Flowable.fromIterable(list).buffer(sqlInfo.numParameters());
        }
    }

    public <T> Flowable<Tx<T>> getInTransaction(Class<T> cls) {
        resolveParameters();
        AtomicReference<Connection> connection = new AtomicReference<Connection>();
        AtomicInteger counter = new AtomicInteger(0);
        return Select
                .create(connections.firstOrError() //
                        .doOnSuccess(c -> connection.set(c)), //
                parameters, //
                sql, //
                rs -> Util.mapObject(rs, cls, 1)) //
                .materialize() //
                .map(n -> toTx(n, connection.get(), counter));
    }

    private static <T> Tx<T> toTx(Notification<T> n, Connection con, AtomicInteger counter) {
        if (n.isOnComplete())
            return new TxImpl<T>(con, null, null, true, counter);
        else if (n.isOnNext())
            return new TxImpl<T>(con, n.getValue(), null, false, counter);
        else
            return new TxImpl<T>(con, null, n.getError(), false, counter);

    }

    private static class TxImpl<T> implements Tx<T> {

        private final Connection con;
        private final T value;
        private final Throwable e;
        private final boolean completed;
        private final AtomicInteger counter;
        private final AtomicBoolean once = new AtomicBoolean(false);

        public TxImpl(Connection con, T value, Throwable e, boolean completed,
                AtomicInteger counter) {
            this.con = con;
            this.value = value;
            this.e = e;
            this.completed = completed;
            this.counter = counter;
        }

        @Override
        public boolean isValue() {
            return !completed && e != null;
        }

        @Override
        public boolean isComplete() {
            return completed;
        }

        @Override
        public boolean isError() {
            return e != null;
        }

        @Override
        public T value() {
            return value;
        }

        @Override
        public Throwable throwable() {
            return e;
        }

        @Override
        public Connection connection() {
            return con;
        }

        @Override
        public void commit() {
            if (once.compareAndSet(false, true))
                if (counter.decrementAndGet() <= 0) {
                    try {
                        con.commit();
                    } catch (SQLException e) {
                        throw new SQLRuntimeException(e);
                    }
                }
        }

        @Override
        public void rollback() {
            if (once.compareAndSet(false, true))
                if (counter.decrementAndGet() <= 0) {
                    try {
                        con.rollback();
                    } catch (SQLException e) {
                        throw new SQLRuntimeException(e);
                    }
                }
        }

    }

}
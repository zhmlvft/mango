/*
 * Copyright 2014 mango.jfaster.org
 *
 * The Mango Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.jfaster.mango;

import org.jfaster.mango.annotation.Cache;
import org.jfaster.mango.annotation.CacheIgnored;
import org.jfaster.mango.annotation.DB;
import org.jfaster.mango.annotation.SQL;
import org.jfaster.mango.base.Config;
import org.jfaster.mango.base.Strings;
import org.jfaster.mango.base.ToStringHelper;
import org.jfaster.mango.base.concurrent.cache.CacheLoader;
import org.jfaster.mango.base.concurrent.cache.DoubleCheckCache;
import org.jfaster.mango.base.concurrent.cache.LoadingCache;
import org.jfaster.mango.base.logging.InternalLogger;
import org.jfaster.mango.base.logging.InternalLoggerFactory;
import org.jfaster.mango.base.sql.OperatorType;
import org.jfaster.mango.base.sql.SQLType;
import org.jfaster.mango.binding.DefaultParameterContext;
import org.jfaster.mango.binding.InvocationContextFactory;
import org.jfaster.mango.binding.ParameterContext;
import org.jfaster.mango.cache.*;
import org.jfaster.mango.datasource.DataSourceFactory;
import org.jfaster.mango.datasource.SimpleDataSourceFactory;
import org.jfaster.mango.exception.DescriptionException;
import org.jfaster.mango.exception.InitializationException;
import org.jfaster.mango.interceptor.Interceptor;
import org.jfaster.mango.interceptor.InterceptorChain;
import org.jfaster.mango.interceptor.InvocationInterceptorChain;
import org.jfaster.mango.jdbc.JdbcOperations;
import org.jfaster.mango.jdbc.JdbcTemplate;
import org.jfaster.mango.operator.BatchUpdateOperator;
import org.jfaster.mango.operator.Operator;
import org.jfaster.mango.operator.QueryOperator;
import org.jfaster.mango.operator.UpdateOperator;
import org.jfaster.mango.operator.datasource.DataSourceGenerator;
import org.jfaster.mango.operator.datasource.DataSourceGeneratorFactory;
import org.jfaster.mango.operator.table.TableGenerator;
import org.jfaster.mango.operator.table.TableGeneratorFactory;
import org.jfaster.mango.parser.ASTRootNode;
import org.jfaster.mango.parser.SqlParser;
import org.jfaster.mango.reflect.AbstractInvocationHandler;
import org.jfaster.mango.reflect.Reflection;
import org.jfaster.mango.reflect.descriptor.*;
import org.jfaster.mango.stat.OperatorStats;
import org.jfaster.mango.stat.StatsCounter;

import javax.annotation.Nullable;
import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * mango框架DAO工厂
 *
 * @author ash
 */
public class Mango {

  private final static InternalLogger logger = InternalLoggerFactory.getInstance(Mango.class);

  /**
   * 数据源工厂
   */
  private DataSourceFactory dataSourceFactory;

  /**
   * 全局缓存处理器
   */
  private CacheHandler defaultCacheHandler;

  /**
   * 全局懒加载，默认为false
   */
  private boolean isDefaultLazyInit = false;

  /**
   * 拦截器链，默认为空
   */
  private InterceptorChain interceptorChain = new InterceptorChain();

  /**
   * jdbc操作
   */
  private JdbcOperations jdbcOperations = new JdbcTemplate();

  /**
   * 参数名发现器
   */
  private ParameterNameDiscover parameterNameDiscover = new SerialNumberParameterNameDiscover();

  /**
   * 统计map
   */
  private final ConcurrentHashMap<Method, StatsCounter> statsCounterMap =
      new ConcurrentHashMap<Method, StatsCounter>();

  /**
   * mango全局配置信息
   */
  private final Config config = new Config();

  /**
   * mango实例
   */
  private final static CopyOnWriteArrayList<Mango> instances = new CopyOnWriteArrayList<Mango>();

  private Mango() {
  }

  public synchronized static Mango newInstance() {
    if (instances.size() == 1) {
      if (logger.isWarnEnabled()) {
        logger.warn("Find out more mango instances, it is recommended to use only one");
      }
    }
    Mango mango = new Mango();
    instances.add(mango);
    return mango;
  }

  public static Mango newInstance(DataSource dataSource) {
    return newInstance().setDataSource(dataSource);
  }

  public static Mango newInstance(DataSourceFactory dataSourceFactory) {
    return newInstance().setDataSourceFactory(dataSourceFactory);
  }

  public static Mango newInstance(DataSourceFactory dataSourceFactory, CacheHandler cacheHandler) {
    return newInstance().setDataSourceFactory(dataSourceFactory).setDefaultCacheHandler(cacheHandler);
  }

  /**
   * 获得mango实例
   */
  public static List<Mango> getInstances() {
    List<Mango> mangos = new ArrayList<Mango>();
    for (Mango instance : instances) {
      mangos.add(instance);
    }
    return Collections.unmodifiableList(mangos);
  }

  /**
   * 添加拦截器
   */
  public Mango addInterceptor(Interceptor interceptor) {
    if (interceptor == null) {
      throw new NullPointerException("interceptor can't be null");
    }
    if (interceptorChain == null) {
      interceptorChain = new InterceptorChain();
    }
    interceptorChain.addInterceptor(interceptor);
    return this;
  }

  /**
   * 创建代理DAO类
   */
  public <T> T create(Class<T> daoClass) {
    return create(daoClass, defaultCacheHandler, isDefaultLazyInit);
  }

  /**
   * 创建代理DAO类，使用特定的{@link CacheHandler}
   */
  public <T> T create(Class<T> daoClass, @Nullable CacheHandler cacheHandler) {
    return create(daoClass, cacheHandler, isDefaultLazyInit);
  }

  /**
   * 创建代理DAO类，自定义是否懒加载
   */
  public <T> T create(Class<T> daoClass, boolean isLazyInit) {
    return create(daoClass, defaultCacheHandler, isLazyInit);
  }

  /**
   * 创建代理DAO类，使用特定的{@link CacheHandler}，自定义是否懒加载
   */
  public <T> T create(Class<T> daoClass, @Nullable CacheHandler cacheHandler, boolean isLazyInit) {
    if (daoClass == null) {
      throw new NullPointerException("dao interface can't be null");
    }

    if (!daoClass.isInterface()) {
      throw new IllegalArgumentException("expected an interface to proxy, but " + daoClass);
    }

    DB dbAnno = daoClass.getAnnotation(DB.class);
    if (dbAnno == null) {
      throw new IllegalStateException("dao interface expected one @DB " +
          "annotation but not found");
    }

    if (cacheHandler == null) {
      cacheHandler = defaultCacheHandler;
    }
    Cache cacheAnno = daoClass.getAnnotation(Cache.class);
    if (cacheAnno != null && cacheHandler == null) {
      throw new IllegalStateException("if @Cache annotation on dao interface, " +
          "cacheHandler can't be null");
    }

    if (dataSourceFactory == null) {
      throw new IllegalArgumentException("dataSourceFactory can't be null");
    }

    MangoInvocationHandler handler = new MangoInvocationHandler(this, cacheHandler);
    if (!isLazyInit) { // 不使用懒加载，则提前加载
      Method[] methods = daoClass.getMethods();
      for (Method method : methods) {
        try {
          handler.getOperator(method);
        } catch (Throwable e) {
          throw new InitializationException("initialize " + ToStringHelper.toString(method) + " error", e);
        }
      }
    }
    return Reflection.newProxy(daoClass, handler);
  }

  /**
   * 返回各个方法对应的状态
   */
  public List<OperatorStats> getAllStats() {
    List<OperatorStats> oss = new ArrayList<OperatorStats>();
    Set<Map.Entry<Method, StatsCounter>> entrySet = statsCounterMap.entrySet();
    for (Map.Entry<Method, StatsCounter> entry : entrySet) {
      Method method = entry.getKey();
      OperatorStats os = entry.getValue().snapshot();
      os.setMethod(method);
      oss.add(os);
    }
    return oss;
  }

  /**
   * 重置各个方法的状态
   */
  public void resetAllStats() {
    Set<Map.Entry<Method, StatsCounter>> entrySet = statsCounterMap.entrySet();
    for (Map.Entry<Method, StatsCounter> entry : entrySet) {
      entry.getValue().reset();
    }
  }

  /**
   * 根据数据源名字获得主库数据源
   */
  public DataSource getMasterDataSource(String database) {
    return dataSourceFactory.getMasterDataSource(database);
  }

  public Mango setDataSource(DataSource dataSource) {
    if (dataSource == null) {
      throw new NullPointerException("dataSource can't be null");
    }
    dataSourceFactory = new SimpleDataSourceFactory(dataSource);
    return this;
  }

  public DataSourceFactory getDataSourceFactory() {
    return dataSourceFactory;
  }

  public Mango setDataSourceFactory(DataSourceFactory dataSourceFactory) {
    if (dataSourceFactory == null) {
      throw new NullPointerException("dataSourceFactory can't be null");
    }
    this.dataSourceFactory = dataSourceFactory;
    return this;
  }

  public CacheHandler getDefaultCacheHandler() {
    return defaultCacheHandler;
  }

  public Mango setDefaultCacheHandler(CacheHandler defaultCacheHandler) {
    if (defaultCacheHandler == null) {
      throw new NullPointerException("defaultCacheHandler can't be null");
    }
    this.defaultCacheHandler = defaultCacheHandler;
    return this;
  }

  public boolean isDefaultLazyInit() {
    return isDefaultLazyInit;
  }

  public Mango setDefaultLazyInit(boolean isDefaultLazyInit) {
    this.isDefaultLazyInit = isDefaultLazyInit;
    return this;
  }

  public Mango setInterceptorChain(InterceptorChain interceptorChain) {
    if (interceptorChain == null) {
      throw new NullPointerException("interceptorChain can't be null");
    }
    this.interceptorChain = interceptorChain;
    return this;
  }

  public JdbcOperations getJdbcOperations() {
    return jdbcOperations;
  }

  public Mango setJdbcOperations(JdbcOperations jdbcOperations) {
    if (jdbcOperations == null) {
      throw new NullPointerException("jdbcOperations can't be null");
    }
    this.jdbcOperations = jdbcOperations;
    return this;
  }

  public ParameterNameDiscover getParameterNameDiscover() {
    return parameterNameDiscover;
  }

  public Mango setParameterNameDiscover(ParameterNameDiscover parameterNameDiscover) {
    if (parameterNameDiscover == null) {
      throw new NullPointerException("parameterNameDiscover can't be null");
    }
    this.parameterNameDiscover = parameterNameDiscover;
    return this;
  }

  public boolean isCompatibleWithEmptyList() {
    return config.isCompatibleWithEmptyList();
  }

  public Mango setCompatibleWithEmptyList(boolean isCompatibleWithEmptyList) {
    config.setCompatibleWithEmptyList(isCompatibleWithEmptyList);
    return this;
  }

  private static class MangoInvocationHandler extends AbstractInvocationHandler implements InvocationHandler {

    private final ConcurrentHashMap<Method, StatsCounter> statsCounterMap;
    private final OperatorFactory operatorFactory;
    private final ParameterNameDiscover parameterNameDiscover;

    private final LoadingCache<Method, Operator> cache = new DoubleCheckCache<Method, Operator>(
        new CacheLoader<Method, Operator>() {
          public Operator load(Method method) {
            if (logger.isInfoEnabled()) {
              logger.info("Initializing operator for {}", ToStringHelper.toString(method));
            }
            StatsCounter statsCounter = getStatusCounter(method);
            long now = System.nanoTime();
            MethodDescriptor md = Methods.getMethodDescriptor(method, parameterNameDiscover);
            Operator operator = operatorFactory.getOperator(md, statsCounter);
            statsCounter.recordInit(System.nanoTime() - now);
            return operator;
          }
        });

    private MangoInvocationHandler(Mango mango, @Nullable CacheHandler cacheHandler) {
      statsCounterMap = mango.statsCounterMap;
      operatorFactory = new OperatorFactory(mango.dataSourceFactory, cacheHandler,
          mango.interceptorChain, mango.jdbcOperations, mango.config);
      parameterNameDiscover = mango.parameterNameDiscover;
    }

    @Override
    protected Object handleInvocation(Object proxy, Method method, Object[] args) throws Throwable {
      if (logger.isDebugEnabled()) {
        logger.debug("Invoking {}", ToStringHelper.toString(method));
      }
      Operator operator = getOperator(method);
      Object r = operator.execute(args);
      return r;
    }

    Operator getOperator(Method method) {
      return cache.get(method);
    }

    /**
     * 一个mango对象可能会创建多个相同的dao，这里多个相同的dao使用同一个StatsCounter
     */
    private StatsCounter getStatusCounter(Method method) {
      StatsCounter statsCounter = statsCounterMap.get(method);
      if (statsCounter == null) {
        statsCounter = new StatsCounter();
        StatsCounter old = statsCounterMap.putIfAbsent(method, statsCounter);
        if (old != null) { // 已经存在，就用老的，这样能保证单例
          statsCounter = old;
        }
      }
      return statsCounter;
    }

  }

  public static class OperatorFactory {

    private final CacheHandler cacheHandler;
    private final InterceptorChain interceptorChain;
    private final JdbcOperations jdbcOperations;
    private final Config config;
    private final TableGeneratorFactory tableGeneratorFactory;
    private final DataSourceGeneratorFactory dataSourceGeneratorFactory;

    public OperatorFactory(DataSourceFactory dataSourceFactory, CacheHandler cacheHandler,
                           InterceptorChain interceptorChain, JdbcOperations jdbcOperations, Config config) {
      this.cacheHandler = cacheHandler;
      this.interceptorChain = interceptorChain;
      this.jdbcOperations = jdbcOperations;
      this.config = config;
      this.tableGeneratorFactory = new TableGeneratorFactory();
      this.dataSourceGeneratorFactory = new DataSourceGeneratorFactory(dataSourceFactory);
    }

    public Operator getOperator(MethodDescriptor md, StatsCounter statsCounter) {
      ASTRootNode rootNode = SqlParser.parse(getSQL(md)).init(); // 初始化抽象语法树
      List<ParameterDescriptor> pds = md.getParameterDescriptors(); // 方法参数描述
      OperatorType operatorType = getOperatorType(pds, rootNode);
      statsCounter.setOperatorType(operatorType);
      if (operatorType == OperatorType.BATCHUPDATE) { // 批量更新重新组装ParameterDescriptorList
        ParameterDescriptor pd = pds.get(0);
        pds = new ArrayList<ParameterDescriptor>(1);
        pds.add(ParameterDescriptor.create(0, pd.getMappedClass(), pd.getAnnotations(), pd.getName()));
      }

      ParameterContext context = DefaultParameterContext.create(pds);
      rootNode.expandParameter(context); // 扩展简化的参数节点
      rootNode.checkAndBind(context); // 检查类型，设定参数绑定器

      // 构造表生成器
      TableGenerator tableGenerator = tableGeneratorFactory.getTableGenerator(md, rootNode, context);

      // 构造数据源生成器
      DataSourceGenerator dataSourceGenerator = dataSourceGeneratorFactory.getDataSourceGenerator(operatorType, md, context);

      Operator operator;
      if (isUseCache(md)) {
        CacheDriver driver = new CacheDriver(md, rootNode, cacheHandler, context, statsCounter);
        statsCounter.setCacheable(true);
        statsCounter.setUseMultipleKeys(driver.isUseMultipleKeys());
        statsCounter.setCacheNullObject(driver.isCacheNullObject());
        switch (operatorType) {
          case QUERY:
            operator = new CacheableQueryOperator(rootNode, md, driver, config);
            break;
          case UPDATE:
            operator = new CacheableUpdateOperator(rootNode, md, driver, config);
            break;
          case BATCHUPDATE:
            operator = new CacheableBatchUpdateOperator(rootNode, md, driver, config);
            break;
          default:
            throw new IllegalStateException();
        }
      } else {
        switch (operatorType) {
          case QUERY:
            operator = new QueryOperator(rootNode, md, config);
            break;
          case UPDATE:
            operator = new UpdateOperator(rootNode, md, config);
            break;
          case BATCHUPDATE:
            operator = new BatchUpdateOperator(rootNode, md, config);
            break;
          default:
            throw new IllegalStateException();
        }
      }

      InvocationInterceptorChain chain =
          new InvocationInterceptorChain(interceptorChain, pds, rootNode.getSQLType());
      operator.setTableGenerator(tableGenerator);
      operator.setDataSourceGenerator(dataSourceGenerator);
      operator.setInvocationContextFactory(InvocationContextFactory.create(context));
      operator.setInvocationInterceptorChain(chain);
      operator.setJdbcOperations(jdbcOperations);
      operator.setStatsCounter(statsCounter);
      return operator;
    }

    String getSQL(MethodDescriptor md) {
      SQL sqlAnno = md.getAnnotation(SQL.class);
      if (sqlAnno == null) {
        throw new DescriptionException("each method expected one @SQL annotation but not found");
      }
      String sql = sqlAnno.value();
      if (Strings.isEmpty(sql)) {
        throw new DescriptionException("sql is null or empty");
      }
      return sql;
    }

    OperatorType getOperatorType(List<ParameterDescriptor> pds, ASTRootNode rootNode) {
      OperatorType operatorType;
      if (rootNode.getSQLType() == SQLType.SELECT) {
        operatorType = OperatorType.QUERY;
      } else {
        operatorType = OperatorType.UPDATE;
        if (pds.size() == 1) { // 只有一个参数
          ParameterDescriptor pd = pds.get(0);
          if (pd.isIterable() && rootNode.getJDBCIterableParameters().isEmpty()) {
            // 参数可迭代，同时sql中没有in语句
            operatorType = OperatorType.BATCHUPDATE;
          }
        }
      }
      return operatorType;
    }

    private boolean isUseCache(MethodDescriptor md) {
      CacheIgnored cacheIgnoredAnno = md.getAnnotation(CacheIgnored.class);
      Cache cacheAnno = md.getAnnotation(Cache.class);
      return cacheAnno != null && cacheIgnoredAnno == null;
    }

  }

}
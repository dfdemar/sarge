package net.jodah.sarge;

import java.lang.reflect.Method;

import net.jodah.sarge.Lifecycle.PreRetry;
import net.jodah.sarge.internal.RetryContextRegistry;
import net.jodah.sarge.internal.RetryDirective;
import net.jodah.sarge.internal.RetryStats;
import net.jodah.sarge.internal.SupervisionRegistry;
import net.jodah.sarge.internal.util.Reflection;
import net.jodah.sarge.internal.util.Types;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Intercepts supervised object method invocations and applies a {@link Plan} for any failures.
 * 
 * @author Jonathan Halterman
 */
public class SupervisedInterceptor implements MethodInterceptor {
  private static final Logger LOG = LoggerFactory.getLogger(SupervisedInterceptor.class);
  private final SupervisionRegistry registry;

  /**
   * Creates a SupervisedInterceptor that intercepts invocations of objects that are supervised by
   * the {@code sarge}.
   */
  public SupervisedInterceptor(Sarge sarge) {
    this.registry = sarge.registry;
  }

  private static Object proxyFor(MethodInvocation invocation) {
    if (Types.isProxy(invocation.getThis().getClass()))
      return invocation.getThis();
    if (Types.GET_PROXY_METHOD != null)
      try {
        return Types.GET_PROXY_METHOD.invoke(invocation, (Object[]) null);
      } catch (Exception e) {
      }
    return null;
  }

  private static boolean shouldSkip(Method method) {
    return method.getDeclaringClass().equals(Object.class)
        || (method.getName().equals("equals") && method.getParameterTypes().length == 1)
        || (method.getName().equals("hashCode") && method.getParameterTypes().length == 0)
        || (method.getName().equals("plan") && method.getParameterTypes().length == 0)
        || (method.getName().equals("selfPlan") && method.getParameterTypes().length == 0);
  }

  public Object invoke(MethodInvocation invocation) throws Throwable {
    Method method = invocation.getMethod();
    if (shouldSkip(method))
      return invocation.proceed();

    Object supervised = proxyFor(invocation);
    if (supervised == null)
      invocation.proceed();

    Supervisor supervisor = registry.supervisorFor(supervised);
    Plan plan = supervisor == null ? registry.planFor(supervised) : supervisor.plan();
    if (plan == null)
      return invocation.proceed();

    Throwable cause = null;
    RetryStats retryStats = null;

    // Retry loop
    while (true) {
      try {
        if (cause != null && supervised instanceof PreRetry)
          ((PreRetry) supervised).preRetry(cause);

        Object result = invocation.proceed();

        if (retryStats != null)
          retryStats.resetBackoff();
        return result;
      } catch (Throwable t) {
        cause = t;
        if (LOG.isDebugEnabled())
          LOG.error("Invocation of {} failed.", Reflection.toString(method), t);
        else if (LOG.isErrorEnabled()) {
          String message = t.getMessage();
          LOG.error("Invocation of {} failed. {}", Reflection.toString(method),
              message == null ? "" : message);
        }

        // Supervision hierarchy traversal
        while (true) {
          Directive directive = plan.apply(cause);
          boolean retry = directive instanceof RetryDirective;

          if (directive == null || Directive.Rethrow.equals(directive)) {
            throw t;
          } else if (Directive.Resume.equals(directive)) {
            if (method.getReturnType().equals(void.class))
              return null;
            throw t;
          } else if (retry || Directive.Escalate.equals(directive)) {
            if (retry) {
              RetryDirective retryDirective = (RetryDirective) directive;
              retryStats = RetryContextRegistry.contextFor(supervised, retryDirective).retryStats;
              if (retryStats.canRetry()) {
                if (retryDirective.shouldBackoff()) {
                  long waitTime = retryStats.getWaitTime();
                  Thread.sleep(waitTime);
                }
                break;
              }
            } else
              retryStats = null;

            if (supervisor == null)
              throw t;
            supervised = supervisor;
            supervisor = registry.supervisorFor(supervisor);
            if (supervisor == null)
              throw t;
            plan = supervisor.plan();
          }
        }
      }
    }
  }
}

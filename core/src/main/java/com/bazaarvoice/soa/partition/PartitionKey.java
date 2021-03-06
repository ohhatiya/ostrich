package com.bazaarvoice.soa.partition;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

@Target({PARAMETER})
@Retention(RUNTIME)
public @interface PartitionKey {
    /**
     * Defines name of the property key, i.e. the key in the {@link com.bazaarvoice.soa.PartitionContext}.
     */
    String value() default "";
}

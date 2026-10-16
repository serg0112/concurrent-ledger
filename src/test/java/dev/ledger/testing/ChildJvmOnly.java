package dev.ledger.testing;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Excluded from the parent suite; direct IDE execution is disabled unless launched by ForkedJvm. */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Tag("child-jvm")
@EnabledIfSystemProperty(named = ForkedJvm.CHILD_PROPERTY, matches = "true")
public @interface ChildJvmOnly {}

package com.toposcribe.calc

import kotlin.math.abs
import org.junit.Assert.assertTrue

/** Tolerancia topográfica -- floats de campo, no igualdad exacta. */
fun assertApprox(expected: Double, actual: Double, eps: Double = 1e-3) {
    assertTrue("esperado=$expected actual=$actual (eps=$eps)", abs(expected - actual) <= eps)
}

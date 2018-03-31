/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.asJava.elements

import com.intellij.psi.*
import com.intellij.psi.impl.LanguageConstantExpressionEvaluator
import com.intellij.psi.impl.ResolveScopeManager
import com.intellij.psi.impl.light.LightIdentifier
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtValueArgument

class KtLightPsiArrayInitializerMemberValue(
    override val kotlinOrigin: KtElement,
    val lightParent: PsiElement,
    val arguments: (KtLightPsiArrayInitializerMemberValue) -> List<PsiAnnotationMemberValue>
) : KtLightElementBase(lightParent), PsiArrayInitializerMemberValue {
    override fun getInitializers(): Array<PsiAnnotationMemberValue> = arguments(this).toTypedArray()

    override fun getParent(): PsiElement = lightParent

    override fun isPhysical(): Boolean = false
}

class KtLightPsiLiteral(
    override val kotlinOrigin: KtExpression,
    val lightParent: PsiElement
) : KtLightElementBase(lightParent), PsiLiteralExpression {

    override fun getValue(): Any? =
        LanguageConstantExpressionEvaluator.INSTANCE.forLanguage(kotlinOrigin.language)?.computeConstantExpression(this, false)

    override fun getType(): PsiType? = PsiType.getJavaLangString(this.manager, ResolveScopeManager.getElementResolveScope(this))

    override fun getParent(): PsiElement = lightParent

    override fun isPhysical(): Boolean = false
}

class KtLightPsiNameValuePair private constructor(
    override val kotlinOrigin: KtElement,
    val valueArgument: KtValueArgument,
    lightParent: PsiElement
) : KtLightElementBase(lightParent),
    PsiNameValuePair {

    constructor(valueArgument: KtValueArgument, lightParent: PsiElement) : this(valueArgument.asElement(), valueArgument, lightParent)

    override fun setValue(newValue: PsiAnnotationMemberValue): PsiAnnotationMemberValue =
        throw UnsupportedOperationException("can't modify KtLightPsiNameValuePair")

    override fun getNameIdentifier(): PsiIdentifier? = LightIdentifier(kotlinOrigin.manager, valueArgument.name)

    override fun getName(): String? = valueArgument.name

    override fun getValue(): PsiAnnotationMemberValue? =
        valueArgument.getArgumentExpression()?.let { convertToLightAnnotationMemberValue(this, it) }

    override fun getLiteralValue(): String? = (getValue() as? PsiLiteralExpression)?.value?.toString()

}
/*
 * AutoRefactor - Eclipse plugin to automatically refactor Java code bases.
 *
 * Copyright (C) 2015-2016 Jean-Noël Rouvignac - initial API and implementation
 * Copyright (C) 2016 Fabrice Tiercelin - Make sure we do not visit again modified nodes
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program under LICENSE-GNUGPL.  If not, see
 * <http://www.gnu.org/licenses/>.
 *
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution under LICENSE-ECLIPSE, and is
 * available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.autorefactor.refactoring.rules;

import static org.autorefactor.refactoring.ASTHelper.DO_NOT_VISIT_SUBTREE;
import static org.autorefactor.refactoring.ASTHelper.VISIT_SUBTREE;
import static org.autorefactor.refactoring.ASTHelper.arg0;
import static org.autorefactor.refactoring.ASTHelper.arguments;
import static org.autorefactor.refactoring.ASTHelper.as;
import static org.autorefactor.refactoring.ASTHelper.asExpression;
import static org.autorefactor.refactoring.ASTHelper.getPreviousSibling;
import static org.autorefactor.refactoring.ASTHelper.getUniqueFragment;
import static org.autorefactor.refactoring.ASTHelper.hasOperator;
import static org.autorefactor.refactoring.ASTHelper.hasType;
import static org.autorefactor.refactoring.ASTHelper.isCastCompatible;
import static org.autorefactor.refactoring.ASTHelper.isMethod;
import static org.autorefactor.refactoring.ASTHelper.isPrimitive;
import static org.autorefactor.refactoring.ASTHelper.isSameLocalVariable;
import static org.autorefactor.refactoring.ASTHelper.match;

import java.util.List;

import org.autorefactor.refactoring.ASTBuilder;
import org.autorefactor.refactoring.ASTSemanticMatcher;
import org.autorefactor.refactoring.BlockSubVisitor;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;

/** See {@link #getDescription()} method. */
public class MapRefactoring extends AbstractRefactoringRule {
    /**
     * Get the name.
     *
     * @return the name.
     */
    public String getName() {
        return "Inited map rather than new map and Map.putAll()";
    }

    /**
     * Get the description.
     *
     * @return the description.
     */
    public String getDescription() {
        return ""
                + "Replaces creating a new Map, then invoking Map.putAll() on it,"
                + " by creating the new Map with the other Map as parameter.";
    }

    /**
     * Get the reason.
     *
     * @return the reason.
     */
    public String getReason() {
        return "It reduces the reading and debugging cost.";
    }

    @Override
    public boolean visit(Block node) {
        final NewAndPutAllMethodVisitor newAndPutAllMethodVisitor = new NewAndPutAllMethodVisitor(ctx, node);
        node.accept(newAndPutAllMethodVisitor);
        return newAndPutAllMethodVisitor.getResult();
    }

    private static final class NewAndPutAllMethodVisitor extends BlockSubVisitor {
        public NewAndPutAllMethodVisitor(final RefactoringContext ctx, final Block startNode) {
            super(ctx, startNode);
        }

        @Override
        public boolean visit(ExpressionStatement node) {
            final MethodInvocation mi = asExpression(node, MethodInvocation.class);
            if (isMethod(mi, "java.util.Map", "putAll", "java.util.Map")) {
                final Expression arg0 = arg0(mi);
                final Statement previousStmt = getPreviousSibling(node);

                final Assignment as = asExpression(previousStmt, Assignment.class);
                if (hasOperator(as, Assignment.Operator.ASSIGN)) {
                    final Expression lhs = as.getLeftHandSide();
                    if (lhs instanceof SimpleName && isSameLocalVariable(lhs, mi.getExpression())) {
                        return maybeReplaceInitializer(as.getRightHandSide(), arg0, node);
                    }
                } else if (previousStmt instanceof VariableDeclarationStatement) {
                    final VariableDeclarationFragment vdf = getUniqueFragment((VariableDeclarationStatement)
                            previousStmt);
                    if (vdf != null && isSameLocalVariable(vdf, mi.getExpression())) {
                        return maybeReplaceInitializer(vdf.getInitializer(), arg0, node);
                    }
                }
            }
            return VISIT_SUBTREE;
        }

        private boolean maybeReplaceInitializer(Expression nodeToReplace,
                final Expression arg0, ExpressionStatement nodeToRemove) {
            final ClassInstanceCreation cic = as(nodeToReplace, ClassInstanceCreation.class);
            if (canReplaceInitializer(cic, arg0)
                    && isCastCompatible(nodeToReplace, arg0)) {
                final ASTBuilder b = ctx.getASTBuilder();
                ctx.getRefactorings().replace(nodeToReplace,
                        b.new0(b.copy(cic.getType()), b.copy(arg0)));
                ctx.getRefactorings().remove(nodeToRemove);
                setResult(DO_NOT_VISIT_SUBTREE);
                return DO_NOT_VISIT_SUBTREE;
            }
            return VISIT_SUBTREE;
        }

        private boolean canReplaceInitializer(final ClassInstanceCreation cic, final Expression sourceMap) {
            if (cic == null) {
                return false;
            }
            final List<Expression> args = arguments(cic);
            final boolean noArgsCtor = args.isEmpty();
            final boolean mapCapacityCtor = isValidCapacityParameter(sourceMap, args);
            return (noArgsCtor && hasType(cic,
                    "java.util.concurrent.ConcurrentHashMap",
                    "java.util.concurrent.ConcurrentSkipListMap",
                    "java.util.Hashtable",
                    "java.util.HashMap",
                    "java.util.IdentityHashMap",
                    "java.util.LinkedHashMap",
                    "java.util.TreeMap",
                    "java.util.WeakHashMap")) || (mapCapacityCtor && hasType(cic,
                            "java.util.concurrent.ConcurrentHashMap",
                            "java.util.Hashtable",
                            "java.util.HashMap",
                            "java.util.IdentityHashMap",
                            "java.util.LinkedHashMap",
                            "java.util.WeakHashMap"));
        }

        private boolean isValidCapacityParameter(Expression sourceMap, final List<Expression> args) {
            if (args.size() == 1 && isPrimitive(args.get(0), "int")) {
                final Object constant = args.get(0).resolveConstantExpressionValue();
                final MethodInvocation mi = as(args.get(0), MethodInvocation.class);
                if (constant != null) {
                    return constant.equals(0);
                } else  {
                    return isMethod(mi, "java.util.Map", "size") && match(new ASTSemanticMatcher(),
                            mi.getExpression(), sourceMap);
                }
            }
            return false;
        }
    }
}

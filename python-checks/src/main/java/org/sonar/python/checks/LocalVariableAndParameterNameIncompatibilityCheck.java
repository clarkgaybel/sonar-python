/*
 * SonarQube Python Plugin
 * Copyright (C) 2011 SonarSource and Waleri Enns
 * dev@sonar.codehaus.org
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02
 */
package org.sonar.python.checks;

import com.sonar.sslr.api.AstNode;
import com.sonar.sslr.api.GenericTokenType;
import com.sonar.sslr.api.Grammar;
import com.sonar.sslr.api.Token;
import org.sonar.api.server.rule.RulesDefinition;
import org.sonar.check.Priority;
import org.sonar.check.Rule;
import org.sonar.check.RuleProperty;
import org.sonar.python.api.PythonGrammar;
import org.sonar.python.api.PythonPunctuator;
import org.sonar.squidbridge.annotations.ActivatedByDefault;
import org.sonar.squidbridge.annotations.SqaleConstantRemediation;
import org.sonar.squidbridge.annotations.SqaleSubCharacteristic;
import org.sonar.squidbridge.checks.SquidCheck;

import java.util.LinkedList;
import java.util.List;
import java.util.regex.Pattern;

@Rule(
    key = LocalVariableAndParameterNameIncompatibilityCheck.CHECK_KEY,
    priority = Priority.MINOR,
    name = "Local variable and function parameter names should comply with a naming convention",
    tags = Tags.CONVENTION
)
@SqaleSubCharacteristic(RulesDefinition.SubCharacteristics.READABILITY)
@SqaleConstantRemediation("2min")
@ActivatedByDefault
public class LocalVariableAndParameterNameIncompatibilityCheck extends SquidCheck<Grammar> {

  public static final String CHECK_KEY = "S117";

  public static final String MESSAGE = "Rename this %s \"%s\" to match the regular expression %s.";
  public static final String PARAMETER = "parameter";
  public static final String LOCAL_VAR = "local variable";

  private static final String CONSTANT_PATTERN = "^[_A-Z][A-Z0-9_]*$";

  private static final String DEFAULT = "^[_a-z][a-z0-9_]*$";
  @RuleProperty(key = "format", defaultValue = DEFAULT)
  public String format = DEFAULT;

  private Pattern pattern = null;
  private Pattern constantPattern = null;

  @Override
  public void init() {
    pattern = Pattern.compile(format);
    constantPattern = Pattern.compile(CONSTANT_PATTERN);
    subscribeTo(PythonGrammar.FUNCDEF);
  }

  @Override
  public void visitNode(AstNode astNode) {
    List<Token> parameters = visitParameters(astNode);
    visitLocalVariables(astNode, parameters);
  }

  private void visitLocalVariables(AstNode funcDef, List<Token> parameters) {
    AstNode suite = funcDef.getFirstChild(PythonGrammar.SUITE);
    if (suite != null) {
      List<AstNode> expressions = suite.getDescendants(PythonGrammar.EXPRESSION_STMT);
      List<Token> varNames = new LinkedList<>();
      List<Token> forCounterNames = getForCounterNames(suite);
      for (AstNode expression : expressions) {
        if (expression.getNumberOfChildren() == 3 && expression.getChildren(PythonPunctuator.ASSIGN) != null && CheckUtils.insideFunction(expression, funcDef)) {
          addNames(varNames, expression, parameters, forCounterNames);
        }
      }
      checkNames(varNames, forCounterNames);
    }
  }

  private void checkNames(List<Token> varNames, List<Token> forCounterNames) {
    for (Token name : varNames) {
      if (!constantPattern.matcher(name.getValue()).matches()) {
        checkName(name, LOCAL_VAR);
      }
    }

    for (Token name : forCounterNames) {
      if (name.getValue().length() > 1) {
        checkName(name, LOCAL_VAR);
      }
    }
  }

  private List<Token> getForCounterNames(AstNode suite) {
    List<AstNode> forStatements = suite.getDescendants(PythonGrammar.FOR_STMT);
    List<Token> result = new LinkedList<>();
    for (AstNode forStatement : forStatements){
      AstNode counters = forStatement.getFirstChild(PythonGrammar.EXPRLIST);
      for (AstNode name : counters.getDescendants(PythonGrammar.NAME)){
        Token token = name.getToken();
        if (token.getType().equals(GenericTokenType.IDENTIFIER)){
          result.add(token);
        }
      }
    }
    return result;
  }

  private void addNames(List<Token> names, AstNode expression, List<Token> parameters, List<Token> forCounters) {
    AstNode leftExpr = expression.getFirstChild(PythonGrammar.TESTLIST_STAR_EXPR);
    List<AstNode> tests = leftExpr.getDescendants(PythonGrammar.TEST);
    for (AstNode test : tests) {
      Token token = test.getToken();
      if (token.getType().equals(GenericTokenType.IDENTIFIER) && isNewVariable(names, parameters, forCounters, token)) {
        names.add(token);
      }
    }

  }

  private boolean isNewVariable(List<Token> names, List<Token> parameters, List<Token> forCounters, Token token) {
    return !contains(names, token) && !contains(parameters, token) && !contains(forCounters, token);
  }

  private boolean contains(List<Token> list, Token token) {
    for (Token currentToken : list) {
      if (currentToken.getValue().equals(token.getValue())){
        return true;
      }
    }
    return false;
  }

  private List<Token> visitParameters(AstNode astNode) {
    List<Token> parameterTokens = new LinkedList<>();
    AstNode varArgList = astNode.getFirstChild(PythonGrammar.VARARGSLIST);
    if (varArgList != null) {
      List<AstNode> funcParameters = varArgList.getDescendants(PythonGrammar.FPDEF);
      funcParameters.addAll(varArgList.getChildren(PythonGrammar.NAME));
      for (AstNode parameter : funcParameters) {
        Token token = parameter.getToken();
        if (token.getType().equals(GenericTokenType.IDENTIFIER)) {
          parameterTokens.add(token);
          checkName(token, PARAMETER);
        }
      }
    }
    return parameterTokens;
  }

  private void checkName(Token token, String type) {
    String name = token.getValue();
    if (!pattern.matcher(name).matches()) {
      getContext().createLineViolation(this, String.format(MESSAGE, type, name, format), token.getLine());
    }
  }

}
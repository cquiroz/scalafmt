package org.scalafmt.internal

import scala.language.implicitConversions

import org.scalafmt.internal.ExpiresOn.Right
import org.scalafmt.internal.ExpiresOn.Left
import org.scalafmt.internal.Length.StateColumn
import org.scalafmt.internal.Length.Num
import org.scalafmt.Error.UnexpectedTree
import org.scalafmt.internal.Policy.NoPolicy
import org.scalafmt.util.Delim
import org.scalafmt.util.Keyword
import org.scalafmt.util.Literal
import org.scalafmt.util.LoggerOps
import org.scalafmt.util.Modifier
import org.scalafmt.util.TokenOps
import org.scalafmt.util.TreeOps
import org.scalafmt.util.Trivia
import scala.collection.mutable
import scala.meta.Tree
import scala.meta.Case
import scala.meta.Defn
import scala.meta.Enumerator
import scala.meta.Import
import scala.meta.Name
import scala.meta.Pat
import scala.meta.Pkg
import scala.meta.Template
import scala.meta.Term
import scala.meta.Type
import scala.meta.tokens.Token

// Too many to import individually.
import scala.meta.tokens.Token._

object Constants {
  val SparkColonNewline = 10
  val BracketPenalty = 20
  val ExceedColumnPenalty = 1000
  // Breaking a line like s"aaaaaaa${111111 + 22222}" should be last resort.
  val BreakSingleLineInterpolatedString = 10 * ExceedColumnPenalty
}

/**
  * Assigns splits to format tokens.
  */
class Router(formatOps: FormatOps) {
  import LoggerOps._
  import TokenOps._
  import TreeOps._
  import formatOps._
  import Constants._

  private def getSplits(formatToken: FormatToken): Seq[Split] = {
    val leftOwner = owners(formatToken.left)
    val rightOwner = owners(formatToken.right)
    val newlines = newlinesBetween(formatToken.between)
    formatToken match {
      case FormatToken(_: BOF, _, _) =>
        Seq(
            Split(NoSplit, 0)
        )
      case FormatToken(_, _: EOF, _) =>
        Seq(
            Split(Newline, 0) // End files with trailing newline
        )
      case FormatToken(start @ Interpolation.Start(), _, _) =>
        val isStripMargin = isMarginizedString(start)
        val end = matchingParentheses(hash(start))
        val policy =
          if (isTripleQuote(start)) NoPolicy
          else penalizeAllNewlines(end, BreakSingleLineInterpolatedString)
        Seq(
            // statecolumn - 1 because of margin characters |
            Split(NoSplit, 0, ignoreIf = !isStripMargin)
              .withPolicy(policy)
              .withIndent(StateColumn, end, Left)
              .withIndent(-1, end, Left),
            Split(NoSplit, 0, ignoreIf = isStripMargin).withPolicy(policy)
        )
      case FormatToken(Interpolation.Id() | Interpolation.Part(_) |
                       Interpolation.Start() | Interpolation.SpliceStart(),
                       _,
                       _) =>
        Seq(
            Split(NoSplit, 0)
        )
      case FormatToken(_,
                       Interpolation.Part(_) | Interpolation.End() |
                       Interpolation.SpliceEnd(),
                       _) =>
        Seq(
            Split(NoSplit, 0)
        )
      case FormatToken(LeftBrace(), RightBrace(), _) =>
        Seq(
            Split(NoSplit, 0)
        )
      // Import
      case FormatToken(Dot(), open @ LeftBrace(), _)
          if parents(rightOwner).exists(_.is[Import]) =>
        Seq(
            Split(NoSplit, 0)
        )
      case FormatToken(open @ LeftBrace(), _, _)
          if parents(leftOwner).exists(_.is[Import]) ||
          leftOwner.is[Term.Interpolate] =>
        val policy =
          if (leftOwner.is[Term.Interpolate]) NoPolicy
          else SingleLineBlock(matchingParentheses(hash(open)))
        Seq(
            Split(if (style.spacesInImportCurlyBrackets) Space else NoSplit, 0)
              .withPolicy(policy)
        )
      case FormatToken(_, close @ RightBrace(), _)
          if parents(rightOwner).exists(_.is[Import]) ||
          rightOwner.is[Term.Interpolate] =>
        Seq(
            Split(if (style.spacesInImportCurlyBrackets) Space else NoSplit, 0)
        )
      case FormatToken(Dot(), underscore @ Underscore(), _)
          if parents(rightOwner).exists(_.is[Import]) =>
        Seq(
            Split(NoSplit, 0)
        )

      // { ... } Blocks
      case tok @ FormatToken(open @ LeftBrace(), right, between) =>
        val nl = Newline(shouldGet2xNewlines(tok))
        val close = matchingParentheses(hash(open))
        val blockSize = close.start - open.end
        val ignore = blockSize > style.maxColumn || isInlineComment(right)
        val newlineBeforeClosingCurly = Policy({
          case Decision(t @ FormatToken(_, `close`, _), s) =>
            Decision(t, Seq(Split(Newline, 0)))
        }, close.end)

        val (startsLambda, lambdaPolicy, lambdaArrow, lambdaIndent) =
          statementStarts
            .get(hash(right))
            .collect {
              case owner: Term.Function =>
                val arrow = lastLambda(owner).tokens.find(_.is[RightArrow])
                val singleLineUntilArrow =
                  newlineBeforeClosingCurly.andThen(SingleLineBlock(
                          arrow.getOrElse(owner.params.last.tokens.last)).f)
                (true, singleLineUntilArrow, arrow, 0)
            }
            .getOrElse {
              leftOwner match {
                // Self type: trait foo { self => ... }
                case t: Template if !t.self.name.is[Name.Anonymous] =>
                  val arrow = t.tokens.find(_.is[RightArrow])
                  val singleLineUntilArrow = newlineBeforeClosingCurly.andThen(
                      SingleLineBlock(arrow.getOrElse(t.self.tokens.last)).f)
                  (true, singleLineUntilArrow, arrow, 2)
                case _ => (false, NoPolicy, None, 0)
              }
            }

        val skipSingleLineBlock =
          ignore || startsLambda || newlinesBetween(between) > 0

        Seq(
            Split(Space, 0, ignoreIf = skipSingleLineBlock)
              .withOptimalToken(close, killOnFail = true)
              .withPolicy(SingleLineBlock(close)),
            Split(Space, 0, ignoreIf = !startsLambda)
              .withOptimalToken(lambdaArrow)
              .withIndent(lambdaIndent, close, Right)
              .withPolicy(lambdaPolicy),
            Split(nl, 1)
              .withPolicy(newlineBeforeClosingCurly)
              .withIndent(2, close, Right)
          )
      // For loop with (
      case tok @ FormatToken(LeftParen(), _, _)
          if leftOwner.is[Term.For] || leftOwner.is[Term.ForYield] =>
        // TODO(olafur) allow newlines?
        Seq(
            Split(NoSplit, 0)
        )

      // Term.Function
      case FormatToken(arrow @ RightArrow(), right, _)
          if statementStarts.contains(hash(right)) &&
          leftOwner.is[Term.Function] =>
        val endOfFunction = leftOwner.tokens.last
        val canBeSpace = statementStarts(hash(right)).is[Term.Function]
        Seq(
            Split(Space, 0, ignoreIf = !canBeSpace),
            Split(Newline, 1).withIndent(2, endOfFunction, Left)
        )
      case FormatToken(arrow @ RightArrow(), right, _)
          if leftOwner.is[Term.Function] =>
        val endOfFunction = functionExpire(
            leftOwner.asInstanceOf[Term.Function])
        Seq(
            Split(Space, 0, ignoreIf = isInlineComment(right))
              .withPolicy(SingleLineBlock(endOfFunction)),
            Split(Newline, 1 + nestedApplies(leftOwner))
              .withIndent(2, endOfFunction, Right)
        )
      // Case arrow
      case tok @ FormatToken(arrow @ RightArrow(), right, between)
          if leftOwner.is[Case] =>
        Seq(
            Split(Space, 0, ignoreIf = newlines != 0), // Gets killed by `case` policy.
            Split(Newline(isDouble = false, noIndent = rhsIsCommentedOut(tok)),
                  1)
        )
      // New statement
      case tok @ FormatToken(Semicolon(), right, between)
          if startsStatement(tok) && newlines == 0 =>
        val expire = statementStarts(hash(right)).tokens.last
        Seq(
            Split(Space, 0)
              .withOptimalToken(expire)
              .withPolicy(SingleLineBlock(expire)),
            // For some reason, this newline cannot cost 1.
            Split(Newline(shouldGet2xNewlines(tok)), 0)
        )

      case tok @ FormatToken(left, right, between) if startsStatement(tok) =>
        val oldNewlines = newlinesBetween(between)
        val newline: Modification = Newline(shouldGet2xNewlines(tok))
        val expire = rightOwner.tokens
          .find(_.is[Equals])
          .getOrElse(rightOwner.tokens.last)

        val spaceCouldBeOk =
          oldNewlines == 0 && !left.is[Comment] && right.is[Keyword] &&
          isSingleIdentifierAnnotation(prev(tok))
        Seq(
            Split(
                  // This split needs to have an optimalAt field.
                  Space,
                  0,
                  ignoreIf = !spaceCouldBeOk)
              .withOptimalToken(expire)
              .withPolicy(SingleLineBlock(expire)),
            // For some reason, this newline cannot cost 1.
            Split(newline, 0)
        )
      case FormatToken(LeftParen(), LeftBrace(), between) =>
        Seq(
            Split(NoSplit, 0)
        )

      // non-statement starting curly brace
      case FormatToken(_, LeftBrace(), between) =>
        Seq(
            Split(Space, 0)
        )

      case FormatToken(_, RightBrace(), _) =>
        Seq(
            Split(Space, 0),
            Split(Newline, 0)
        )
      case FormatToken(left @ KwPackage(), _, _) if leftOwner.is[Pkg] =>
        Seq(
            Split(Space, 0)
        )
      // Opening [ with no leading space.
      // Opening ( with no leading space.
      case FormatToken(KwThis() | Ident(_) | RightBracket() | RightBrace() |
                       RightParen(),
                       LeftParen() | LeftBracket(),
                       _) if noSpaceBeforeOpeningParen(rightOwner) =>
        Seq(
            Split(NoSplit, 0)
        )
      // Defn.{Object, Class, Trait}
      case tok @ FormatToken(KwObject() | KwClass() | KwTrait(), _, _) =>
        val expire = defnTemplate(leftOwner)
          .flatMap(templateCurly)
          .getOrElse(leftOwner.tokens.last)
        val forceNewlineBeforeExtends = Policy({
          case Decision(t @ FormatToken(_, right @ KwExtends(), _), s)
              if owners(right) == leftOwner =>
            Decision(t, s.filter(_.modification.isNewline))
        }, expire.end)
        Seq(
            Split(Space, 0)
              .withOptimalToken(expire, killOnFail = true)
              .withPolicy(SingleLineBlock(expire)),
            Split(Space, 1).withPolicy(forceNewlineBeforeExtends)
        )
      case FormatToken(open @ LeftParen(), right, _)
          if style.binPackParameters && isDefnSite(leftOwner) =>
        val close = matchingParentheses(hash(open))
        val indent = Num(style.continuationIndentDefnSite)
        Seq(
            Split(NoSplit, 0).withIndent(indent, close, Left),
            Split(Newline, 1, ignoreIf = right.is[RightParen])
              .withIndent(indent, close, Left)
        )
      // DefDef
      case tok @ FormatToken(KwDef(), name @ Ident(_), _) =>
        Seq(
            Split(Space, 0)
        )
      case tok @ FormatToken(e @ Equals(), right, _)
          if defBody(leftOwner).isDefined =>
        val expire = defBody(leftOwner).get.tokens.last
        val exclude = getExcludeIf(expire, {
          case RightBrace() => true
          case close @ RightParen()
              if opensConfigStyle(
                  leftTok2tok(matchingParentheses(hash(close)))) =>
            // Example:
            // def x = foo(
            //     1
            // )
            true
          case _ => false
        })

        val rhsIsJsNative = isJsNative(right)
        Seq(
            Split(Space,
                  0,
                  ignoreIf = newlines > 0 && !rhsIsJsNative,
                  policy = SingleLineBlock(expire, exclude = exclude)),
            Split(Newline, 1, ignoreIf = rhsIsJsNative)
              .withIndent(2, expire, Left)
        )
      // Named argument foo(arg = 1)
      case tok @ FormatToken(e @ Equals(), right, _) if (leftOwner match {
            case t: Term.Arg.Named => true
            case t: Term.Param if t.default.isDefined => true
            case _ => false
          }) =>
        val rhsBody = leftOwner match {
          case t: Term.Arg.Named => t.rhs
          case t: Term.Param => t.default.get
          case t => throw UnexpectedTree[Term.Param](t)
        }
        val expire = rhsBody.tokens.last
        val exclude = insideBlock(formatToken, expire, _.is[LeftBrace])
        val unindent = Policy(UnindentAtExclude(exclude, Num(-2)), expire.end)
        Seq(
            Split(Space, 0).withIndent(2, expire, Left).withPolicy(unindent)
        )
      case tok @ FormatToken(open @ LeftParen(), _, _)
          if style.binPackParameters && isDefnSite(leftOwner) =>
        Seq(
            Split(NoSplit, 0)
        )
      // Term.Apply and friends
      case FormatToken(LeftParen() | LeftBracket(), _, between)
          if style.configStyleArguments &&
          (isDefnSite(leftOwner) || isCallSite(leftOwner)) &&
          opensConfigStyle(formatToken) =>
        val open = formatToken.left
        val indent = getApplyIndent(leftOwner)
        val close = matchingParentheses(hash(open))
        val oneArgOneLine = OneArgOneLineSplit(open)
        val configStyle = oneArgOneLine.copy(
            f = oneArgOneLine.f.orElse {
          case Decision(t @ FormatToken(_, `close`, _), splits) =>
            Decision(t, Seq(Split(Newline, 0)))
        })
        Seq(
            Split(Newline, 0, policy = configStyle)
              .withIndent(indent, close, Right)
        )
      case FormatToken(LeftParen() | LeftBracket(), _, _)
          if style.binPackArguments && isCallSite(leftOwner) =>
        val open = formatToken.left
        val close = matchingParentheses(hash(open))
        val optimal = leftOwner.tokens.find(_.is[Comma]).orElse(Some(close))
        Seq(
            Split(NoSplit, 0)
              .withOptimalToken(optimal)
              .withIndent(4, close, Left),
            Split(Newline, 2).withIndent(4, close, Left)
        )
      case tok @ FormatToken(LeftParen() | LeftBracket(), right, between)
          if !isSuperfluousParenthesis(formatToken.left, leftOwner) &&
          (!style.binPackArguments && isCallSite(leftOwner)) ||
          (!style.binPackParameters && isDefnSite(leftOwner)) =>
        val open = tok.left
        val close = matchingParentheses(hash(open))
        val (lhs, args) = splitApplyIntoLhsAndArgsLifted(leftOwner).getOrElse {
          logger.error(s"""Unknown tree
                          |${log(leftOwner.parent.get)}
                          |${isDefnSite(leftOwner)}""".stripMargin)
          throw UnexpectedTree[Term.Apply](leftOwner)
        }
        // In long sequence of select/apply, we penalize splitting on
        // parens furthest to the right.
        val lhsPenalty = treeDepth(lhs)

        val isBracket = open.is[LeftBracket]
        val bracketMultiplier =
          if (isBracket) Constants.BracketPenalty
          else 1

        val nestedPenalty = nestedApplies(leftOwner)
        val exclude =
          if (isBracket) insideBlock(tok, close, _.is[LeftBracket])
          else insideBlock(tok, close, x => x.is[LeftBrace])
        val excludeRanges = exclude.map(parensRange)

        val indent = getApplyIndent(leftOwner)

        // It seems acceptable to unindent by the continuation indent inside
        // curly brace wrapped blocks.
        val unindent = UnindentAtExclude(exclude, Num(-4))
        val singleArgument = args.length == 1

        def singleLine(newlinePenalty: Int): Policy = {
          val baseSingleLinePolicy =
            if (isBracket) {
              if (singleArgument)
                SingleLineBlock(
                    close, excludeRanges, disallowInlineComments = false)
              else SingleLineBlock(close)
            } else {
              if (singleArgument) {
                penalizeAllNewlines(close, newlinePenalty)
              } else SingleLineBlock(close, excludeRanges)
            }

          if (exclude.isEmpty || isBracket) baseSingleLinePolicy
          else baseSingleLinePolicy.andThen(unindent)
        }

        val oneArgOneLine = OneArgOneLineSplit(open)

        val modification =
          if (right.is[Comment]) newlines2Modification(between)
          else NoSplit

        val newlineModification: Modification =
          if (right.is[Comment] && newlinesBetween(between) == 0) Space
          else Newline

        val charactersInside = (close.start - open.end) - 2

        val fitsOnOneLine =
          singleArgument || excludeRanges.nonEmpty ||
          charactersInside <= style.maxColumn

        val expirationToken: Token =
          if (isDefnSite(leftOwner)) defnSiteLastToken(leftOwner)
          else rhsOptimalToken(leftTok2tok(close))

        val tooManyArguments = args.length > 100

        Seq(
            Split(modification,
                  0,
                  policy = singleLine(6),
                  ignoreIf = !fitsOnOneLine)
              .withOptimalToken(expirationToken)
              .withIndent(indent, close, Left),
            Split(newlineModification,
                  (1 + nestedPenalty + lhsPenalty) * bracketMultiplier,
                  policy = singleLine(5),
                  ignoreIf = !fitsOnOneLine)
              .withOptimalToken(expirationToken)
              .withIndent(indent, close, Left),
            // TODO(olafur) singleline per argument!
            Split(modification,
                  (2 + lhsPenalty) * bracketMultiplier,
                  policy = oneArgOneLine,
                  ignoreIf = singleArgument || tooManyArguments)
              .withOptimalToken(expirationToken)
              .withIndent(StateColumn, close, Right),
            Split(Newline,
                  (3 + nestedPenalty + lhsPenalty) * bracketMultiplier,
                  policy = oneArgOneLine,
                  ignoreIf = singleArgument)
              .withOptimalToken(expirationToken)
              .withIndent(indent, close, Left)
          )

      // Closing def site ): ReturnType
      case FormatToken(close @ RightParen(), colon @ Colon(), _)
          if style.allowNewlineBeforeColonInMassiveReturnTypes &&
          defDefReturnType(leftOwner).isDefined =>
        val expire = lastToken(defDefReturnType(rightOwner).get)
        val penalizeNewlines = penalizeAllNewlines(
            expire, Constants.BracketPenalty)
        Seq(
            Split(NoSplit, 0).withPolicy(penalizeNewlines),
            // Spark style guide allows this:
            // https://github.com/databricks/scala-style-guide#indent
            Split(Newline, Constants.SparkColonNewline)
              .withIndent(2, expire, Left)
              .withPolicy(penalizeNewlines)
          )

      // Delim
      case FormatToken(_, Comma(), _) =>
        Seq(
            Split(NoSplit, 0)
        )
      // These are mostly filtered out/modified by policies.
      case tok @ FormatToken(Comma(), right, _) =>
        // TODO(olafur) DRY, see OneArgOneLine.
        val rhsIsAttachedComment =
          tok.right.is[Comment] && newlinesBetween(tok.between) == 0
        val penalizeNewlineInNextArg: Policy =
          argumentStarts.get(hash(right)) match {
            case Some(nextArg) if isBinPack(leftOwner) =>
              penalizeAllNewlines(nextArg.tokens.last, 1)
            case _ => NoPolicy
          }
        Seq(
            Split(Space, 0).withPolicy(penalizeNewlineInNextArg),
            Split(Newline, 1, ignoreIf = rhsIsAttachedComment)
        )
      case FormatToken(_, Semicolon(), _) =>
        Seq(
            Split(NoSplit, 0)
        )
      // Return always gets space
      case FormatToken(KwReturn(), _, _) =>
        Seq(
            Split(Space, 0)
        )
      case FormatToken(left @ Ident(_), Colon(), _)
          if rightOwner.is[Type.Param] =>
        Seq(
            Split(Space, 0)
        )
      case FormatToken(left @ Ident(_), Colon(), _) =>
        Seq(
            Split(identModification(left), 0)
        )
      case FormatToken(_, Colon(), _) =>
        Seq(
            Split(NoSplit, 0)
        )
      // Only allow space after = in val if rhs is a single line or not
      // an infix application or an if. For example, this is allowed:
      // val x = function(a,
      //                  b)
      case FormatToken(tok @ Equals(), right, between)
          if leftOwner.is[Defn.Val] || leftOwner.is[Defn.Var] =>
        val rhs: Tree = leftOwner match {
          case l: Defn.Val => l.rhs
          case r: Defn.Var =>
            r.rhs match {
              case Some(x) => x
              case None => r // var x: Int = _, no policy
            }
        }
        val expire = rhs.tokens.last
        val spacePolicy: Policy = rhs match {
          case _: Term.ApplyInfix | _: Term.If => SingleLineBlock(expire)
          case _ => NoPolicy
        }

        val mod: Modification =
          if (isAttachedComment(right, between)) Space
          else Newline

        Seq(
            Split(Space, 0, policy = spacePolicy),
            Split(mod, 1, ignoreIf = isJsNative(right))
              .withIndent(2, expire, Left)
        )
      case tok @ FormatToken(left, dot @ Dot(), _)
          if rightOwner.is[Term.Select] &&
          isOpenApply(next(next(tok)).right) && !left.is[Underscore] &&
          !parents(rightOwner).exists(_.is[Import]) =>
        val owner = rightOwner.asInstanceOf[Term.Select]
        val nestedPenalty = nestedSelect(rightOwner) + nestedApplies(leftOwner)
        val chain = getSelectChain(owner)
        val lastToken = lastTokenInChain(chain)
        val optimalToken = chainOptimalToken(chain)
        val breakOnEveryDot = Policy({
          case Decision(t @ FormatToken(left, dot2 @ Dot(), _), s)
              if chain.contains(owners(dot2)) =>
            Decision(t, Seq(Split(Newline, 1)))
        }, lastToken.end)
        val exclude = getExcludeIf(lastToken)
        // This policy will apply to both the space and newline splits, otherwise
        // the newline is too cheap even it doesn't actually prevent other newlines.
        val penalizeNewlinesInApply = penalizeAllNewlines(lastToken, 2)
        val noSplitPolicy = SingleLineBlock(lastToken, exclude)
          .andThen(penalizeNewlinesInApply.f)
          .copy(expire = lastToken.end)
        val newlinePolicy = breakOnEveryDot
          .andThen(penalizeNewlinesInApply.f)
          .copy(expire = lastToken.end)
        Seq(
            Split(NoSplit, 0)
              .withOptimalToken(optimalToken, killOnFail = false)
              .withPolicy(noSplitPolicy),
            Split(Newline.copy(acceptNoSplit = true), 2 + nestedPenalty)
              .withPolicy(newlinePolicy)
              .withIndent(2, lastToken, Left)
          )
      // ApplyUnary
      case tok @ FormatToken(Ident(_), Literal(), _)
          if leftOwner == rightOwner =>
        Seq(
            Split(NoSplit, 0)
        )
      case tok @ FormatToken(op @ Ident(_), _, _) if leftOwner.parent.exists {
            case unary: Term.ApplyUnary => unary.op.tokens.head == op
            case _ => false
          } =>
        Seq(
            Split(NoSplit, 0)
        )
      // Annotations, see #183 for discussion on this.
      case FormatToken(_, bind @ At(), _) if rightOwner.is[Pat.Bind] =>
        Seq(
            Split(Space, 0)
        )
      case FormatToken(bind @ At(), _, _) if leftOwner.is[Pat.Bind] =>
        Seq(
            Split(Space, 0)
        )
      case FormatToken(At(), right @ Delim(), _) =>
        Seq(Split(NoSplit, 0))
      case FormatToken(At(), right @ Ident(_), _) =>
        // Add space if right starts with a symbol
        Seq(Split(identModification(right), 0))

      // Template
      case FormatToken(_, right @ KwExtends(), _) =>
        val lastToken = defnTemplate(rightOwner)
          .flatMap(templateCurly)
          .getOrElse(rightOwner.tokens.last)
        Seq(
            Split(Space, 0)
              .withPolicy(SingleLineBlock(lastToken))
              .withIndent(Num(4), lastToken, Left),
            Split(Newline, 1).withIndent(Num(4), lastToken, Left)
        )
      case tok @ FormatToken(_, right @ KwWith(), _)
          if rightOwner.is[Template] =>
        val template = rightOwner
        val expire = templateCurly(rightOwner)
        Seq(
            Split(Space, 0),
            Split(Newline, 1).withPolicy(Policy({
              // Force template to be multiline.
              case d @ Decision(FormatToken(open @ LeftBrace(), right, _),
                                splits)
                  if !right.is[RightBrace] && // corner case, body is {}
                  childOf(template, owners(open)) =>
                d.copy(splits = splits.filter(_.modification.isNewline))
            }, expire.end))
        )
      // If
      case FormatToken(open @ LeftParen(), _, _) if leftOwner.is[Term.If] =>
        val close = matchingParentheses(hash(open))
        val penalizeNewlines = penalizeNewlineByNesting(open, close)
        Seq(
            Split(NoSplit, 0)
              .withIndent(StateColumn, close, Left)
              .withPolicy(penalizeNewlines)
          )
      case FormatToken(close @ RightParen(), right, between)
          if leftOwner.is[Term.If] && !isFirstOrLastToken(close, leftOwner) =>
        val owner = leftOwner.asInstanceOf[Term.If]
        val expire = owner.thenp.tokens.last
        val rightIsOnNewLine = newlines > 0
        // Inline comment attached to closing RightParen
        val attachedComment = !rightIsOnNewLine && isInlineComment(right)
        val newlineModification: Modification =
          if (attachedComment)
            Space // Inline comment will force newline later.
          else Newline
        Seq(
            Split(Space, 0, ignoreIf = attachedComment)
              .withIndent(2, expire, Left)
              .withPolicy(SingleLineBlock(expire)),
            Split(newlineModification, 1).withIndent(2, expire, Left)
        )
      case tok @ FormatToken(RightBrace(), els @ KwElse(), _) =>
        Seq(
            Split(Space, 0)
        )
      case tok @ FormatToken(_, els @ KwElse(), _) =>
        val expire = rhsOptimalToken(leftTok2tok(rightOwner.tokens.last))
        Seq(
            Split(Space, 0, ignoreIf = newlines > 0)
              .withOptimalToken(expire)
              .withPolicy(SingleLineBlock(expire)),
            Split(Newline, 1)
        )
      // Last else branch
      case tok @ FormatToken(els @ KwElse(), _, _)
          if !nextNonComment(tok).right.is[KwIf] =>
        val expire = leftOwner match {
          case t: Term.If => t.elsep.tokens.last
          case x => throw new UnexpectedTree[Term.If](x)
        }
        Seq(
            Split(Space, 0, policy = SingleLineBlock(expire)),
            Split(Newline, 1).withIndent(2, expire, Left)
        )

      // Type variance
      case tok @ FormatToken(Ident(_), Ident(_), _)
          if isTypeVariant(leftOwner) =>
        Seq(
            Split(NoSplit, 0)
        )

      // Var args
      case FormatToken(_, Ident("*"), _) if rightOwner.is[Type.Arg.Repeated] =>
        Seq(
            Split(NoSplit, 0)
        )

      // ApplyInfix.
      case FormatToken(open @ LeftParen(), right, _)
          if leftOwner.is[Term.ApplyInfix] =>
        val close = matchingParentheses(hash(open))
        val indent: Length = right match {
          case KwIf() => StateColumn
          case _ => Num(4)
        }
        Seq(
            Split(NoSplit, 0).withIndent(indent, close, Left)
        )
      case FormatToken(_, open @ LeftParen(), _)
          if rightOwner.is[Term.ApplyInfix] =>
        val close = matchingParentheses(hash(open))
        val optimalToken = Some(OptimalToken(close))
        Seq(
            Split(Space, 0, optimalAt = optimalToken)
              .withPolicy(SingleLineBlock(close)),
            Split(Newline, 1, optimalAt = optimalToken)
        )
      // Infix operator.
      case tok @ FormatToken(op @ Ident(_), _, _) if leftOwner.parent.exists {
            case infix: Term.ApplyInfix => infix.op == owners(op)
            case _ => false
          } =>
        val owner = leftOwner.parent.get.asInstanceOf[Term.ApplyInfix]
        val isAssignment = isAssignmentOperator(op)
        val isBool = isBoolOperator(op)
        // TODO(olafur) Document that we only allow newlines for this subset
        // of infix operators. To force a newline for other operators it's
        // possible to wrap arguments in parentheses.
        val newlineOk =
          isAssignment || isBool || newlineOkOperators.contains(op.syntax)
        val newlineCost =
          if (isAssignment || isBool) 1
          else if (newlineOk) 3
          else 0 // Ignored
        val indent =
          if (isAssignment) 2
          else 0
        // Optimization, assignment operators make the state space explode in
        // sbt build files because of := operators everywhere.
        val optimalToken =
          if (isAssignment) Some(OptimalToken(owner.args.last.tokens.last))
          else None
        Seq(
            Split(Space, 0, optimalAt = optimalToken),
            Split(Newline, newlineCost, ignoreIf = !newlineOk)
              .withIndent(indent, formatToken.right, Left)
        )

      // Pat
      case tok @ FormatToken(Ident("|"), _, _)
          if leftOwner.is[Pat.Alternative] =>
        Seq(
            Split(Space, 0),
            Split(Newline, 1)
        )
      case tok @ FormatToken(
          Ident(_) | Literal() | Interpolation.End() | Xml.End(),
          Ident(_) | Literal() | Xml.Start(),
          _) =>
        Seq(
            Split(Space, 0)
        )

      // Case
      case tok @ FormatToken(_, KwMatch(), _) =>
        Seq(
            Split(Space, 0)
        )

      // Protected []
      case tok @ FormatToken(_, LeftBracket(), _)
          if isModPrivateProtected(leftOwner) =>
        Seq(
            Split(NoSplit, 0)
        )
      case tok @ FormatToken(LeftBracket(), _, _)
          if isModPrivateProtected(leftOwner) =>
        Seq(
            Split(NoSplit, 0)
        )

      // Case
      case tok @ FormatToken(cs @ KwCase(), _, _) if leftOwner.is[Case] =>
        val owner = leftOwner.asInstanceOf[Case]
        val arrow = getArrow(owner)
        // TODO(olafur) expire on token.end to avoid this bug.
        val expire = Option(owner.body)
          .filter(_.tokens.exists(!_.is[Trivia]))
          .map(lastToken)
          .map(getRightAttachedComment)
          .getOrElse(arrow) // edge case, if body is empty expire on arrow.

        Seq(
            // Either everything fits in one line or break on =>
            Split(Space, 0)
              .withOptimalToken(expire, killOnFail = true)
              .withPolicy(SingleLineBlock(expire)),
            Split(Space, 1)
              .withPolicy(Policy({
                case Decision(t @ FormatToken(`arrow`, right, between), s)
                    // TODO(olafur) any other corner cases?
                    if !right.is[LeftBrace] &&
                    !isAttachedComment(right, between) =>
                  Decision(t, s.filter(_.modification.isNewline))
              }, expire = expire.end))
              .withIndent(2, expire, Left) // case body indented by 2.
              .withIndent(2, arrow, Left) // cond body indented by 4.
        )
      case tok @ FormatToken(_, cond @ KwIf(), _) if rightOwner.is[Case] =>
        val arrow = getArrow(rightOwner.asInstanceOf[Case])
        val exclude = insideBlock(tok, arrow, _.is[LeftBrace]).map(parensRange)
        val singleLine = SingleLineBlock(arrow, exclude = exclude)
        Seq(
            Split(Space, 0, policy = singleLine),
            Split(Newline, 1)
        )
      // Inline comment
      case FormatToken(_, c: Comment, between) =>
        Seq(Split(newlines2Modification(between), 0))
      // Commented out code should stay to the left
      case FormatToken(c: Comment, _, between) if c.syntax.startsWith("//") =>
        Seq(Split(Newline, 0))
      case FormatToken(c: Comment, _, between) =>
        Seq(Split(newlines2Modification(between), 0))

      // Term.ForYield
      case tok @ FormatToken(_, arrow @ KwIf(), _)
          if rightOwner.is[Enumerator.Guard] =>
        Seq(
            // Either everything fits in one line or break on =>
            Split(Space, 0),
            Split(Newline, 1).withIndent(4, leftOwner.tokens.last, Left)
        )
      case tok @ FormatToken(arrow @ LeftArrow(), _, _)
          if leftOwner.is[Enumerator.Generator] =>
        val lastToken =
          findSiblingGuard(leftOwner.asInstanceOf[Enumerator.Generator])
            .map(_.tokens.last)
            .getOrElse(rightOwner.tokens.last)
        Seq(
            // Either everything fits in one line or break on =>
            Split(Space, 0).withIndent(StateColumn, lastToken, Left)
        )
      case tok @ FormatToken(KwYield(), right, _)
          if leftOwner.is[Term.ForYield] && !right.is[LeftBrace] =>
        val lastToken = leftOwner.asInstanceOf[Term.ForYield].body.tokens.last
        Seq(
            // Either everything fits in one line or break on =>
            Split(Space, 0).withPolicy(SingleLineBlock(lastToken)),
            Split(Newline, 1).withIndent(2, lastToken, Left)
        )
      // Interpolation
      case FormatToken(_, Interpolation.Id() | Xml.Start(), _) =>
        Seq(
            Split(Space, 0)
        )
      case FormatToken(Interpolation.Id() | Xml.Start(), _, _) =>
        Seq(
            Split(NoSplit, 0)
        )
      // Throw exception
      case FormatToken(KwThrow(), _, _) =>
        Seq(
            Split(Space, 0)
        )
      // Open paren generally gets no space.
      case FormatToken(LeftParen(), _, _) =>
        Seq(
            Split(NoSplit, 0)
        )

      // Singleton types
      case FormatToken(_, KwType(), _) if rightOwner.is[Type.Singleton] =>
        Seq(
            Split(NoSplit, 0)
        )
      // seq to var args foo(seq:_*)
      case FormatToken(Colon(), Underscore(), _)
          if next(formatToken).right.syntax == "*" =>
        Seq(
            Split(Space, 0)
        )
      case FormatToken(Underscore(), asterisk @ Ident("*"), _)
          if prev(formatToken).left.is[Colon] =>
        Seq(
            Split(NoSplit, 0)
        )
      // Xml
      case FormatToken(Xml.Part(_), _, _) =>
        Seq(
            Split(NoSplit, 0)
        )
      case FormatToken(_, Xml.Part(_), _) =>
        Seq(
            Split(NoSplit, 0)
        )

      // Fallback
      case FormatToken(_, Dot() | Hash(), _) =>
        Seq(
            Split(NoSplit, 0)
        )
      case FormatToken(Dot() | Hash(), Ident(_) | KwThis(), _) =>
        Seq(
            Split(NoSplit, 0)
        )
      case FormatToken(_, RightBracket() | RightParen(), _) =>
        Seq(
            Split(NoSplit, 0)
        )
      case FormatToken(_, Keyword(), _) =>
        Seq(
            Split(Space, 0)
        )
      case FormatToken(Keyword() | Modifier(), _, _) =>
        Seq(
            Split(Space, 0)
        )
      case FormatToken(LeftBracket(), _, _) =>
        Seq(
            Split(NoSplit, 0)
        )
      case FormatToken(_, Delim(), _) =>
        Seq(
            Split(Space, 0)
        )
      case FormatToken(Delim(), _, _) =>
        Seq(
            Split(Space, 0)
        )
      case tok =>
        logger.debug("MISSING CASE:\n" + log(tok))
        Seq() // No solution available, partially format tree.
    }
  }

  // TODO(olafur) replace cache with array of seq[split]
  private val cache = mutable.Map.empty[FormatToken, Seq[Split]]

  /**
    * Assigns possible splits to a FormatToken.
    *
    * The FormatToken can be considered as a node in a graph and the
    * splits as edges. Given a format token (a node in the graph), Route
    * determines which edges lead out from the format token.
    */
  def getSplitsMemo(formatToken: FormatToken): Seq[Split] =
    cache.getOrElseUpdate(formatToken, {
      val splits = getSplits(formatToken).map(_.adapt(formatToken))
      formatToken match {
        // TODO(olafur) refactor into "global policy"
        // Only newlines after inline comments.
        case FormatToken(c: Comment, _, _) if c.syntax.startsWith("//") =>
          val newlineSplits = splits.filter(_.modification.isNewline)
          if (newlineSplits.isEmpty) Seq(Split(Newline, 0))
          else newlineSplits
        case _ => splits
      }
    })

  private implicit def int2num(n: Int): Num = Num(n)
}

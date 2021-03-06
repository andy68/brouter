package btools.expressions;


final class BExpression
{
  private static final int OR_EXP = 10;
  private static final int AND_EXP = 11;
  private static final int NOT_EXP = 12;

  private static final int ADD_EXP = 20;
  private static final int MULTIPLY_EXP = 21;
  private static final int MAX_EXP = 22;

  private static final int SWITCH_EXP = 30;
  private static final int ASSIGN_EXP = 31;
  private static final int LOOKUP_EXP = 32;
  private static final int NUMBER_EXP = 33;
  private static final int VARIABLE_EXP = 34;

  private static final int DUMPPOS_EXP = 40;

  private int typ;
  private BExpression op1;
  private BExpression op2;
  private BExpression op3;
  private float numberValue;
  private int variableIdx;
  private int lookupNameIdx;
  private int lookupValueIdx;

  // Parse the expression and all subexpression
  public static BExpression parse( BExpressionContext ctx, int level ) throws Exception
  {
    String operator = ctx.parseToken();
    if ( operator == null )
    {
      if ( level == 0 ) return null;
      else throw new IllegalArgumentException( "unexpected end of file" );
    }

    if ( level == 0 )
    {
      if ( !"assign".equals( operator ) )
      {
        throw new IllegalArgumentException( "operator " + operator + " is invalid on toplevel (only 'assign' allowed)" );
      }
    }

    BExpression exp = new BExpression();
    int nops = 3;

    if ( "switch".equals( operator ) )
    {
      exp.typ = SWITCH_EXP;
    }
    else
    {
      nops = 2; // check binary expressions

      if ( "or".equals( operator ) )
      {
        exp.typ = OR_EXP;
      }
      else if ( "and".equals( operator ) )
      {
        exp.typ = AND_EXP;
      }
      else if ( "multiply".equals( operator ) )
      {
        exp.typ = MULTIPLY_EXP;
      }
      else if ( "add".equals( operator ) )
      {
        exp.typ = ADD_EXP;
      }
      else if ( "max".equals( operator ) )
      {
        exp.typ = MAX_EXP;
      }
      else
      {
        nops = 1; // check unary expressions
        if ( "assign".equals( operator ) )
        {
          if ( level > 0 ) throw new IllegalArgumentException( "assign operator within expression" );
          exp.typ = ASSIGN_EXP;
          String variable = ctx.parseToken();
          if ( variable == null ) throw new IllegalArgumentException( "unexpected end of file" );
          exp.variableIdx = ctx.getVariableIdx( variable, true );
          if ( exp.variableIdx < ctx.getMinWriteIdx() ) throw new IllegalArgumentException( "cannot assign to readonly variable " + variable );
        }
        else if ( "not".equals( operator ) )
        {
          exp.typ = NOT_EXP;
        }
        else if ( "dumppos".equals( operator ) )
        {
          exp.typ = DUMPPOS_EXP;
        }
        else
        {
          nops = 0; // check elemantary expressions
          int idx = operator.indexOf( '=' );
          if ( idx >= 0 )
          {
            exp.typ = LOOKUP_EXP;
            String name = operator.substring( 0, idx );
            String value = operator.substring( idx+1 );

            exp.lookupNameIdx = ctx.getLookupNameIdx( name );
            if ( exp.lookupNameIdx < 0 )
            {
              throw new IllegalArgumentException( "unknown lookup name: " + name );
            }
            exp.lookupValueIdx = ctx.getLookupValueIdx( exp.lookupNameIdx, value );
            if ( exp.lookupValueIdx < 0 )
            {
              throw new IllegalArgumentException( "unknown lookup value: " + value );
            }
          }
          else if ( (idx = ctx.getVariableIdx( operator, false )) >= 0 )
          {
            exp.typ = VARIABLE_EXP;
            exp.variableIdx = idx;
          }
          else
          {
            try
            {
              exp.numberValue = Float.parseFloat( operator );
              exp.typ = NUMBER_EXP;
            }
            catch( NumberFormatException nfe )
            {
              throw new IllegalArgumentException( "unknown expression: " + operator );
            }
          }
        }
      }
    }
    // parse operands
    if ( nops > 0  ) exp.op1 = BExpression.parse( ctx, level+1 );
    if ( nops > 1  ) exp.op2 = BExpression.parse( ctx, level+1 );
    if ( nops > 2  ) exp.op3 = BExpression.parse( ctx, level+1 );
    return exp;
  }

  // Evaluate the expression
  public float evaluate( BExpressionContext ctx )
  {
    switch( typ )
    {
      case OR_EXP: return op1.evaluate(ctx) != 0.f ? 1.f : ( op2.evaluate(ctx) != 0.f ? 1.f : 0.f );
      case AND_EXP: return op1.evaluate(ctx) != 0.f ? ( op2.evaluate(ctx) != 0.f ? 1.f : 0.f ) : 0.f;
      case ADD_EXP: return op1.evaluate(ctx) + op2.evaluate(ctx);
      case MULTIPLY_EXP: return op1.evaluate(ctx) * op2.evaluate(ctx);
      case MAX_EXP: return max( op1.evaluate(ctx), op2.evaluate(ctx) );
      case SWITCH_EXP: return op1.evaluate(ctx) != 0.f ? op2.evaluate(ctx) : op3.evaluate(ctx);
      case ASSIGN_EXP: return ctx.assign( variableIdx, op1.evaluate(ctx) );
      case LOOKUP_EXP: return ctx.getLookupMatch( lookupNameIdx, lookupValueIdx );
      case NUMBER_EXP: return numberValue;
      case VARIABLE_EXP: return ctx.getVariableValue( variableIdx );
      case NOT_EXP: return op1.evaluate(ctx) == 0.f ? 1.f : 0.f;
      case DUMPPOS_EXP: ctx.expressionWarning( "INFO" ); return op1.evaluate(ctx);
      default: throw new IllegalArgumentException( "unknown op-code: " + typ );
    }
  }

  private float max( float v1, float v2 )
  {
    return v1 > v2 ? v1 : v2;
  }
}

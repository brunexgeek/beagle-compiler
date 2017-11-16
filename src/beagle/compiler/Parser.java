package beagle.compiler;

import java.util.LinkedList;
import java.util.List;

import beagle.compiler.tree.Annotation;
import beagle.compiler.tree.CompilationUnit;
import beagle.compiler.tree.ConstantDeclaration;
import beagle.compiler.tree.FormalParameter;
import beagle.compiler.tree.IAnnotation;
import beagle.compiler.tree.ICompilationUnit;
import beagle.compiler.tree.IConstantDeclaration;
import beagle.compiler.tree.IFormalParameter;
import beagle.compiler.tree.IMethodDeclaration;
import beagle.compiler.tree.IModifiers;
import beagle.compiler.tree.IName;
import beagle.compiler.tree.IPackage;
import beagle.compiler.tree.ITreeElement;
import beagle.compiler.tree.ITypeDeclaration;
import beagle.compiler.tree.ITypeImport;
import beagle.compiler.tree.ITypeReference;
import beagle.compiler.tree.IVariableDeclaration;
import beagle.compiler.tree.MethodDeclaration;
import beagle.compiler.tree.Name;
import beagle.compiler.tree.Package;
import beagle.compiler.tree.TypeBody;
import beagle.compiler.tree.TypeDeclaration;
import beagle.compiler.tree.TypeImport;
import beagle.compiler.tree.TypeReference;
import beagle.compiler.tree.VariableDeclaration;

public class Parser implements IParser
{

	private String fileName;

	private TokenArray tokens;

	private CompilationContext context;

	public Parser( CompilationContext context, IScanner scanner )
	{
		fileName = scanner.getFileName();
		tokens = new TokenArray(scanner, 16);
		this.context = context;
	}

	boolean expected( TokenType... types )
	{
		for (TokenType type : types )
		{
			if (tokens.peek().type == type) return true;
		}

		context.throwExpected(tokens.peek(), types);
		return false;
	}

	void discardWhiteSpaces()
	{
		while (true)
		{
			TokenType current = tokens.peek().type;
			if (current == TokenType.TOK_EOL)
				continue;
			break;
		}
	}

	/**
	 * Parse a compilation unit.
	 *
	 *   Unit: PackageDeclaration? ImportDeclaration* TypeDeclaration+
	 *
	 * @return
	 */
	@Override
	public ICompilationUnit parse()
	{
		Token current = tokens.peek();
		IPackage pack = null;

		if (tokens.peekType() == TokenType.TOK_PACKAGE)
			pack = parsePackage();

		ICompilationUnit unit = new CompilationUnit(fileName, pack);

		current = tokens.peek();
		while (current != null && current.type == TokenType.TOK_IMPORT)
		{
			ITypeImport imp = parseImport();
			unit.addImport(imp);
			if (imp == null) break;
			current = tokens.peek();
		}

		while (tokens.peek().type != TokenType.TOK_EOF)
		{
			ITypeDeclaration type = parseType(unit);
			if (type == null) return null;
			unit.addType(type);
		}
		return unit;
	}


	IName parseName()
	{
		return parseName(true);
	}


	/**
	 * Parse a name.
	 *
	 *   QualifiedName := TOK_NAME [ "." TOK_NAME ]*
	 *
	 * @return
	 */
	IName parseName( boolean isQualified )
	{
		if (!expected(TokenType.TOK_NAME))
			return null;

		Name result = new Name(tokens.peek().value);
		tokens.discard();

		while (isQualified)
		{
			if (!tokens.lookahead(TokenType.TOK_DOT, TokenType.TOK_NAME))
				break;
			result.append(tokens.peek(1).value);
			tokens.discard(2);
		}

		return result;
	}

	/**
	 * Parse a package declaration.
	 *
	 *  Package: "package" QualifiedName
	 *
	 * @return
	 */
	IPackage parsePackage()
	{
		if (tokens.peekType() == TokenType.TOK_PACKAGE)
		{
			tokens.discard();
			IName name = parseName();
			//tokens.discard(TokenType.TOK_EOL);
			return new Package(name);
		}
		return null;
	}

	/**
	 * Parse a import declaration.
	 *
	 *  Import: "import" QualifiedName ( "." "*" | "as" Name )?
	 *
	 * @return
	 */
	ITypeImport parseImport()
	{
		if (tokens.peekType() == TokenType.TOK_IMPORT)
		{
			tokens.discard();
			IName qualifiedName = parseName();
			if (tokens.lookahead(TokenType.TOK_DOT, TokenType.TOK_MUL))
			{
				tokens.discard(2);
				//tokens.discard(TokenType.TOK_EOL);
				return new TypeImport(context, qualifiedName);
			}
			else
			{
				if (!qualifiedName.isQualified())
				{
					context.listener.onError(null, "Invalid qualified type name");
					return null;
				}
				IName typeName = qualifiedName.slice(qualifiedName.getCount() - 1);
				IName packageName = qualifiedName.slice(0, qualifiedName.getCount() - 1);
				//tokens.discard(TokenType.TOK_EOL);
				return new TypeImport(context, packageName, typeName);
			}
		}
		return null;
	}


	/**
	 * Parse a type definition:
	 *
	 *   Type: Class
	 *
	 * @return
	 */
	ITypeDeclaration parseType( ICompilationUnit unit )
	{
		List<IAnnotation> annots = parseAnnotations();
		//IModifiers modifiers = parseModifiers(false);

		if (tokens.peekType() == TokenType.TOK_CLASS)
			return parseClass(unit, annots, null);

		return null;
	}


	/**
	 * Parse a class definition.
	 *
	 *    Class: Annotation* "class" QualifiedName Extends? ClassBody?
	 *
	 * @param unit
	 * @param annots
	 * @param modifiers
	 * @return
	 */
	ITypeDeclaration parseClass( ICompilationUnit unit, List<IAnnotation> annots, IModifiers modifiers )
	{
		if (!expected(TokenType.TOK_CLASS))
			return null;
		tokens.discard();

		List<ITypeReference> extended = null;

		IName name = parseName();

		if (tokens.peekType() == TokenType.TOK_COLON)
			extended = parseExtends();

		if (tokens.peekType() == TokenType.TOK_EOL) tokens.discard();

		TypeBody body = parseClassBody();

		return new TypeDeclaration(unit, annots, modifiers, name, extended, body);
	}

	/**
	 * Parse a class body.
	 *
	 *	  ClassBody: "{" Member* "}"
	 *
	 *    Member: ( Variable | Constant | Method )*
	 *
	 * @return
	 */
	TypeBody parseClassBody()
	{
		if (tokens.peekType() == TokenType.TOK_LEFT_BRACE)
		{
			tokens.discard();

			TypeBody body = new TypeBody();

			// parse every class member
			while (tokens.peekType() != TokenType.TOK_RIGHT_BRACE)
			{
				List<IAnnotation> annots = parseAnnotations();
				//IModifiers modifiers = parseModifiers();

				// variable or constant
				if (tokens.peekType() == TokenType.TOK_CONST)
					body.getConstants().add( (IConstantDeclaration) parseVariableOrConstant(annots) );
				else
				if (tokens.peekType() == TokenType.TOK_VAR)
					body.getVariables().add( (IVariableDeclaration) parseVariableOrConstant(annots) );
				else
				if (tokens.peekType() == TokenType.TOK_DEF)
				{
					body.getMethods().add( parseMethod(annots, body) );
				}
				else
				{
					context.throwExpected(tokens.peek(), TokenType.TOK_VAR, TokenType.TOK_CONST);
					break;
				}
			}

			tokens.discard();
			return body;
		}

		return null;
	}

	/**
	 * Parse a variable.
	 *
	 *    Variable: Annotation* "var" Name ( ":" TypeReference )? ( "=" Expression )?
	 *
	 *    Constant: Annotation* "const" Name ( ":" TypeReference )? "=" Expression
	 *
	 * @return
	 */
	ITreeElement parseVariableOrConstant( List<IAnnotation> annots )
	{
		// get 'var' or 'const' keyword
		TokenType kind = tokens.peekType();
		tokens.discard();

		IName name = parseName(false);
		ITypeReference type = null;

		// check whether we have the var/const type
		if (tokens.peekType() == TokenType.TOK_COLON)
		{
			tokens.discard(1);
			type = new TypeReference( parseName() );
		}

		if (tokens.peekType() == TokenType.TOK_ASSIGN)
		{
			tokens.discard(); // =
			tokens.discard();
			// TODO: parse expression
		}
		else
		if (kind == TokenType.TOK_CONST)
		{
			expected(TokenType.TOK_ASSIGN);
			return null;
		}

		if (kind == TokenType.TOK_CONST)
			return new ConstantDeclaration(annots, type, name);
		else
			return new VariableDeclaration(annots, type, name);
	}


	/**
	 * Parse a method.
	 *
	 *    Method: Annotation* “def” Name ParameterList ( ":" TypeReference )? Block?
	 *
	 * @param modifiers
	 * @param type
	 * @param body
	 */
	IMethodDeclaration parseMethod( List<IAnnotation> annots, TypeBody body )
	{
		if (!expected(TokenType.TOK_DEF)) return null;
		tokens.discard();

		ITypeReference type = null;
		IName name = parseName();

		if (!expected(TokenType.TOK_LEFT_PAR)) return null;
		List<IFormalParameter> params = parseFormalParameters();

		if (tokens.peekType() == TokenType.TOK_COLON)
		{
			tokens.discard(1);
			type = new TypeReference( parseName() );
		}

		IMethodDeclaration method = new MethodDeclaration(annots, type, name, params, null);
		method.setParent(body);

		return method;
	}

	/**
	 * Parse a parameter list.
	 *
	 *    ParameterList: "(" ")" | "(" Parameter ( "," Parameter )* ")"
	 *
	 *    Parameter: ( "var" | "const" )? Name ":" TypeReference
	 *
	 * @return
	 */
	List<IFormalParameter> parseFormalParameters()
	{
		if (tokens.peekType() != TokenType.TOK_LEFT_PAR) return null;
		tokens.discard();

		List<IFormalParameter> output = new LinkedList<>();
		IName typeName, name;

		while (tokens.peekType() != TokenType.TOK_RIGHT_PAR)
		{
			if (tokens.peekType() == TokenType.TOK_COMA)
			{
				tokens.discard();
				continue;
			}

			name = parseName();
			if (!expected(TokenType.TOK_COLON)) return null;
			tokens.discard();
			typeName = parseName();
			output.add( new FormalParameter(name, new TypeReference(typeName)) );
		}

		tokens.discard(); // )
		return output;
	}

	/**
	 * Parse the following grammar:
	 *
	 *    Extends: ":" TypeReference ( "," TypeReference )*
	 *
	 * @return
	 */
	List<ITypeReference> parseExtends()
	{
		List<ITypeReference> extended = null;

		if (tokens.peekType() == TokenType.TOK_COLON)
		{
			tokens.discard();

			if (tokens.peekType() != TokenType.TOK_NAME)
			{
				context.listener.onError(null, "Expected identifier");
				return null;
			}

			extended = new LinkedList<ITypeReference>();
			extended.add( new TypeReference( parseName() ) );

			while (true)
			{
				if (tokens.peekType() != TokenType.TOK_COMA)
					break;

				tokens.discard();
				extended.add( new TypeReference( parseName() ) );
			}
		}

		return extended;
	}

	/**
	 * Parse a annatation.
	 *
	 *    Annotation: "@" QualifiedName
	 *
	 * @return
	 */
	List<IAnnotation> parseAnnotations()
	{
		if (tokens.peekType() != TokenType.TOK_AT)
		{
			return new LinkedList<IAnnotation>();
		}

		LinkedList<IAnnotation> output = new LinkedList<>();

		while (tokens.peekType() == TokenType.TOK_AT)
		{
			tokens.discard();
			IName name = parseName();
			if (name == null) return null;
			output.add( new Annotation( new TypeReference(name)));
		}

		return output;
	}

}

package beagle.compiler.tree;

public class StringLiteral extends Literal<String>
{

	public StringLiteral(String value)
	{
		super(value);
	}

	@Override
	public void accept(ITreeVisitor visitor)
	{
		visitor.visit(this);
		visitor.finish(this);
	}

}

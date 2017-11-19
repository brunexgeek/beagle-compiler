package beagle.compiler.tree;


public abstract class TreeElement implements ITreeElement
{

	protected ITreeElement parent;

	@Override
	public void accept(ITreeVisitor visitor, ITreeElement child)
	{
		if (child != null) child.accept(visitor);
	}

	@Override
	public ITreeElement parent()
	{
		return parent;
	}

	@Override
	public void parent(ITreeElement parent)
	{
		this.parent = parent;
	}

	/*@Override
	public <T extends ITreeElement> void accept(ITreeVisitor visitor, List<T> child)
	{
		if (child == null) return;

		for (T item : child)
			item.accept(visitor);
	}*/

}
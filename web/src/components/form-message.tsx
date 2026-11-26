export function FormMessage({ message }: { message: string }) {
  return message ? (
    <p role="alert" className="text-caption font-medium text-danger">
      {message}
    </p>
  ) : null;
}

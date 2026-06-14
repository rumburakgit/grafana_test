FROM python:3.12-slim
WORKDIR /app
RUN pip install requests --no-cache-dir
COPY load_generator.py .
CMD ["python", "-u", "load_generator.py"]
